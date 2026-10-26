package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;
import com.sondertara.joya.exceptions.IllegalSqlException;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.beans.Introspector;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
public class JPAQueryParser<T> extends AbstractQueryParser {
    private static final String KEYWORD_TEMPLATE = "(%s)(?=(\\p{Lu}|\\P{InBASIC_LATIN}))";
    private static final String QUERY_PATTERN = "find|read|get|query|search|stream";
    private static final String COUNT_PATTERN = "count";
    private static final String UPDATE_PATTERN = "update";
    private static final String EXISTS_PATTERN = "exists";
    private static final String DELETE_PATTERN = "delete|remove";
    private static final Pattern PREFIX_TEMPLATE = Pattern.compile(
            "^(" + QUERY_PATTERN + "|" + COUNT_PATTERN + "|" + UPDATE_PATTERN + "|" + EXISTS_PATTERN + "|" + DELETE_PATTERN + ")((\\p{Lu}.*?))??By");
    private static final Pattern ORDER_BY_SPLIT_PATTERN = Pattern.compile("OrderBy(?=\\p{Lu})");
    private static final Pattern ORDER_DIR_PATTERN = Pattern.compile("(Asc|Desc)(?=$|\\p{Lu})");

    private final @Nullable Object[] runtimeArgs;
    private final NamingPolicy namingPolicy;
    private Subject subject;
    private Predicate predicate;

    public JPAQueryParser(Class<T> domainClass, Method method) {
        this(domainClass, method, null, NamingPolicy.DEFAULT);
    }

    public JPAQueryParser(Class<T> domainClass, Method method, @Nullable Object[] runtimeArgs) {
        this(domainClass, method, runtimeArgs, NamingPolicy.DEFAULT);
    }

    public JPAQueryParser(Class<T> domainClass, Method method, @Nullable Object[] runtimeArgs, @Nullable NamingPolicy namingPolicy) {
        super(domainClass, method);
        this.runtimeArgs = runtimeArgs;
        this.namingPolicy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
    }

    @Override
    public EntityBasedBoundSql parse() {
        String methodName = statementMethod.getName();
        Matcher matcher = PREFIX_TEMPLATE.matcher(methodName);

        String remainder;
        if (!matcher.find()) {
            this.subject = new Subject(Optional.empty());
            remainder = methodName;
        } else {
            this.subject = new Subject(Optional.of(matcher.group(0)));
            remainder = methodName.substring(matcher.group().length());
        }

        String predicateSource = remainder;
        String orderBySource = null;
        Matcher orderMatcher = ORDER_BY_SPLIT_PATTERN.matcher(remainder);
        if (orderMatcher.find()) {
            predicateSource = remainder.substring(0, orderMatcher.start());
            orderBySource = remainder.substring(orderMatcher.end());
        }

        this.predicate = new Predicate(predicateSource, domainClass);

        StringBuilder candidate = new StringBuilder();
        AtomicInteger loop = new AtomicInteger(0);
        List<PredicatePart> parts = new ArrayList<>();

        List<OrPart> list = this.predicate.nodes();
        for (int j = 0; j < list.size(); j++) {
            OrPart part = list.get(j);
            boolean needVisitNext = false;
            for (int i = 0; i < part.getChildren().size(); i++) {
                loop.set(i);
                PredicatePart predicatePart = findPart(loop, candidate, part.getChildren());
                if (null != predicatePart) {
                    if (loop.get() == part.getChildren().size() - 1) {
                        predicatePart.setOrPartEnd(true);
                    }
                    parts.add(predicatePart);
                    candidate.delete(0, candidate.length());
                    if (loop.get() > i) {
                        i = loop.get();
                    }
                } else {
                    needVisitNext = true;
                    candidate.append("Or");
                    break;
                }
            }
            if (needVisitNext) {
                continue;
            }
        }

        if (candidate.length() > 0) {
            throw new IllegalSqlException(
                    "No property name found by name({}) for the method({}), please check it",
                    candidate, statementMethod.getName());
        }

        List<List<PredicatePart>> orGroups = new ArrayList<>();
        List<PredicatePart> currentAndGroup = new ArrayList<>();
        for (PredicatePart part : parts) {
            currentAndGroup.add(part);
            if (part.isOrPartEnd()) {
                orGroups.add(new ArrayList<>(currentAndGroup));
                currentAndGroup.clear();
            }
        }
        if (!currentAndGroup.isEmpty()) {
            orGroups.add(currentAndGroup);
        }

        EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(domainClass);
        List<JoyaExecutorSupport.ParameterBinding> bindings = new ArrayList<>();
        AtomicInteger argPosition = new AtomicInteger(0);
        boolean cacheable = parts.stream().noneMatch(p ->
                p.getMethodPredicateType() == MethodPredicateType.IN
                        || p.getMethodPredicateType() == MethodPredicateType.NOT_IN);

        List<String> updateSetColumns = new ArrayList<>();
        Map<String, Object> updateSetValues = new LinkedHashMap<>();
        boolean updateIgnoreNull = false;
        Runnable postUpdateAction = null;

        if (subject.isUpdate()) {
            String updateBody = subject.getUpdateBody();
            boolean isIgnoreNullKeyword = "IgnoreNull".equals(updateBody) && resolveColumn(meta, "ignoreNull") == null;
            boolean isEntityUpdateMode = updateBody.isEmpty() || isIgnoreNullKeyword;
            if (isEntityUpdateMode) {
                int entityPos = argPosition.incrementAndGet();
                if (runtimeArgs == null || runtimeArgs.length < entityPos || runtimeArgs[entityPos - 1] == null) {
                    throw new IllegalArgumentException(
                            "Entity argument at position 0 must not be null for method: " + statementMethod.getName());
                }
                Object entityArg = runtimeArgs[entityPos - 1];
                if (entityArg instanceof EntityListener listener) {
                    listener.preUpdate(entityArg);
                    postUpdateAction = () -> listener.postUpdate(entityArg);
                }
                EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, entityArg);
                updateIgnoreNull = isIgnoreNullKeyword;
                List<String> updatable = new ArrayList<>(meta.updatableColumns());
                if (meta.primaryKey() != null) {
                    updatable.remove(meta.primaryKey());
                }
                for (String col : updatable) {
                    Object val = snapshot.value(col);
                    if (updateIgnoreNull && val == null) {
                        continue;
                    }
                    updateSetColumns.add(col);
                    updateSetValues.put(col, val);
                    bindings.add(JoyaExecutorSupport.ParameterBinding.constant(val));
                }
                cacheable = false;
            } else {
                updateSetColumns.addAll(parseUpdateSetColumns(meta, updateBody));
                for (int i = 0; i < updateSetColumns.size(); i++) {
                    int pos = argPosition.incrementAndGet();
                    bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
                }
            }
        }

        WhereCriterion whereCriterion;
        if (orGroups.size() <= 1) {
            whereCriterion = new WhereCriterion(LinkType.AND);
            if (orGroups.size() == 1) {
                for (PredicatePart predicatePart : orGroups.get(0)) {
                    applyPredicatePart(whereCriterion, predicatePart, argPosition, bindings);
                }
            }
        } else {
            whereCriterion = new WhereCriterion(LinkType.OR);
            for (List<PredicatePart> andGroup : orGroups) {
                whereCriterion.and(condition -> {
                    for (PredicatePart predicatePart : andGroup) {
                        applyPredicatePart(condition, predicatePart, argPosition, bindings);
                    }
                    return condition;
                });
            }
        }

        java.lang.reflect.Type returnType = statementMethod.getGenericReturnType();

        String sql;
        if (subject.isDelete()) {
            MutationContext deleteCtx = new MutationContext(meta, Map.of(), whereCriterion, Set.of(), false, false, namingPolicy);
            sql = DeleteStatementFactory.create(deleteCtx).sql();
        } else if (subject.isUpdate()) {
            MutationContext updateCtx = new MutationContext(
                    meta,
                    updateSetValues,
                    whereCriterion,
                    new LinkedHashSet<>(updateSetColumns),
                    updateIgnoreNull,
                    false,
                    namingPolicy);
            sql = UpdateStatementFactory.create(updateCtx).sql();
        } else if (subject.isCountProjection() || subject.isExistsProjection()) {
            Set<String> countColumns = new LinkedHashSet<>();
            countColumns.add("count(*)");
            sql = SelectStatementFactory.create(meta, whereCriterion, countColumns, null, namingPolicy).sql();
        } else {
            String orderByClause = parseOrderByClause(meta, orderBySource);
            sql = SelectStatementFactory.create(meta, whereCriterion, null, orderByClause, namingPolicy).sql();
            if (subject.isDistinct()) {
                sql = SqlRenderer.renderDistinct(sql);
            }
        }

        java.util.OptionalInt maxResults = subject.getMaxResults()
                .map(java.util.OptionalInt::of)
                .orElseGet(java.util.OptionalInt::empty);
        return new DefaultEntityBasedBoundSql(returnType, sql, bindings, cacheable, maxResults, postUpdateAction);
    }

    private List<String> parseUpdateSetColumns(EntityMetadataContext meta, String updateSubjectBody) {
        String[] tokens = Pattern.compile(String.format(KEYWORD_TEMPLATE, "And")).split(updateSubjectBody);
        List<String> columns = new ArrayList<>();
        StringBuilder candidate = new StringBuilder();
        for (String token : tokens) {
            if (!Strings.hasText(token)) {
                continue;
            }
            if (candidate.isEmpty()) {
                candidate.append(Introspector.decapitalize(token));
            } else {
                candidate.append("And").append(token);
            }
            String col = resolveColumn(meta, candidate.toString());
            if (col != null) {
                if (meta.nonUpdatableColumns().contains(col)) {
                    throw new IllegalSqlException(
                            "Column [{}] is marked as updatable=false and cannot be updated in method [{}]",
                            col, statementMethod.getName());
                }
                if (columns.contains(col)) {
                    throw new IllegalSqlException(
                            "Duplicate update column [{}] in method [{}]",
                            col, statementMethod.getName());
                }
                columns.add(col);
                candidate.setLength(0);
            }
        }
        if (!candidate.isEmpty()) {
            throw new IllegalSqlException(
                    "No property name found in update subject({}) for the method({}), please check it",
                    candidate, statementMethod.getName());
        }
        return columns;
    }

    private @Nullable String parseOrderByClause(EntityMetadataContext meta, @Nullable String orderBySource) {
        if (orderBySource == null || orderBySource.isBlank()) {
            return null;
        }
        Matcher matcher = ORDER_DIR_PATTERN.matcher(orderBySource);
        List<String> orderItems = new ArrayList<>();
        int start = 0;
        while (matcher.find()) {
            String rawProp = orderBySource.substring(start, matcher.start());
            if (rawProp.startsWith("And") && rawProp.length() > 3 && Character.isUpperCase(rawProp.charAt(3))
                    && resolveColumn(meta, Introspector.decapitalize(rawProp)) == null) {
                rawProp = rawProp.substring(3);
            }
            String col = resolveColumn(meta, Introspector.decapitalize(rawProp));
            if (col == null) {
                continue;
            }
            String dir = matcher.group(1).equalsIgnoreCase("Desc") ? "DESC" : "ASC";
            orderItems.add(meta.formatColumn(col, namingPolicy) + " " + dir);
            start = matcher.end();
        }
        if (start < orderBySource.length()) {
            String remaining = orderBySource.substring(start);
            if (remaining.startsWith("And") && remaining.length() > 3 && Character.isUpperCase(remaining.charAt(3))
                    && resolveColumn(meta, Introspector.decapitalize(remaining)) == null) {
                remaining = remaining.substring(3);
            }
            String col = resolveColumn(meta, Introspector.decapitalize(remaining));
            if (col == null) {
                throw new IllegalSqlException(
                        "No property name found in OrderBy clause({}) for the method({}), please check it",
                        remaining, statementMethod.getName());
            }
            orderItems.add(meta.formatColumn(col, namingPolicy) + " ASC");
        }
        return orderItems.isEmpty() ? null : SqlRenderer.renderOrderBy(String.join(", ", orderItems));
    }

    private static @Nullable String resolveColumn(EntityMetadataContext meta, String fieldOrColumn) {
        String col = meta.columnByField(fieldOrColumn);
        if (col != null) {
            return col;
        }
        return meta.canonicalColumn(fieldOrColumn);
    }

    private void applyPredicatePart(WhereCriterion target,
                                    PredicatePart predicatePart,
                                    AtomicInteger argPosition,
                                    List<JoyaExecutorSupport.ParameterBinding> bindings) {
        MethodPredicateType type = predicatePart.getMethodPredicateType();
        String col = predicatePart.getPropertyPath().getName();

        switch (type) {
            case IS_NULL, IS_EMPTY -> target.isNull(col);
            case IS_NOT_NULL, IS_NOT_EMPTY -> target.isNotNull(col);
            case TRUE -> {
                target.eq(col, Boolean.TRUE);
                bindings.add(JoyaExecutorSupport.ParameterBinding.constant(Boolean.TRUE));
            }
            case FALSE -> {
                target.eq(col, Boolean.FALSE);
                bindings.add(JoyaExecutorSupport.ParameterBinding.constant(Boolean.FALSE));
            }
            case BETWEEN -> {
                int pos1 = argPosition.incrementAndGet();
                int pos2 = argPosition.incrementAndGet();
                target.between(col, List.of("__P1__", "__P2__"));
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos1));
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos2));
            }
            case IN, NOT_IN -> {
                int pos = argPosition.incrementAndGet();
                List<Object> items = resolveCollectionArgument(pos);
                if (items != null) {
                    if (items.isEmpty()) {
                        throw new IllegalArgumentException("IN / NOT_IN collection parameter must not be empty for column: " + col);
                    }
                    if (type == MethodPredicateType.IN) {
                        target.in(col, items);
                    } else {
                        target.notIn(col, items);
                    }
                    for (Object item : items) {
                        bindings.add(JoyaExecutorSupport.ParameterBinding.constant(item));
                    }
                } else {
                    if (type == MethodPredicateType.IN) {
                        target.in(col, List.of("__P__"));
                    } else {
                        target.notIn(col, List.of("__P__"));
                    }
                    bindings.add(new JoyaExecutorSupport.InParameterBinding(pos));
                }
            }
            case STARTING_WITH -> {
                int pos = argPosition.incrementAndGet();
                target.startsWith(col, "__P__");
                bindings.add(JoyaExecutorSupport.ParameterBinding.like(pos, false, true));
            }
            case ENDING_WITH -> {
                int pos = argPosition.incrementAndGet();
                target.endsWith(col, "__P__");
                bindings.add(JoyaExecutorSupport.ParameterBinding.like(pos, true, false));
            }
            case LIKE, CONTAINING -> {
                int pos = argPosition.incrementAndGet();
                target.contains(col, "__P__");
                bindings.add(JoyaExecutorSupport.ParameterBinding.like(pos, true, true));
            }
            case NOT_LIKE, NOT_CONTAINING -> {
                int pos = argPosition.incrementAndGet();
                target.notContains(col, "__P__");
                bindings.add(JoyaExecutorSupport.ParameterBinding.like(pos, true, true));
            }
            case LESS_THAN, BEFORE -> {
                int pos = argPosition.incrementAndGet();
                target.lt(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
            case LESS_THAN_EQUAL -> {
                int pos = argPosition.incrementAndGet();
                target.lte(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
            case GREATER_THAN, AFTER -> {
                int pos = argPosition.incrementAndGet();
                target.gt(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
            case GREATER_THAN_EQUAL -> {
                int pos = argPosition.incrementAndGet();
                target.gte(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
            case NEGATING_SIMPLE_PROPERTY -> {
                int pos = argPosition.incrementAndGet();
                target.ne(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
            default -> {
                int pos = argPosition.incrementAndGet();
                target.eq(col, "__P__");
                bindings.add(new JoyaExecutorSupport.ParameterBinding(pos));
            }
        }
    }

    @Nullable
    private List<Object> resolveCollectionArgument(int position) {
        if (runtimeArgs == null || position - 1 >= runtimeArgs.length) {
            return null;
        }
        Object arg = runtimeArgs[position - 1];
        if (arg == null) {
            return List.of();
        }
        if (arg instanceof Collection<?> col) {
            return new ArrayList<>(col);
        }
        if (arg.getClass().isArray()) {
            int len = Array.getLength(arg);
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                list.add(Array.get(arg, i));
            }
            return list;
        }
        return List.of(arg);
    }

    private PredicatePart findPart(AtomicInteger loop, StringBuilder prefix, List<PredicatePart> list) {
        PredicatePart predicatePart = list.get(loop.get());
        PropertyPath propertyPath = predicatePart.getPropertyPath();
        String decapitalize = Introspector.decapitalize(prefix + propertyPath.getName());

        if (prefix.length() == 0) {
            prefix.append(decapitalize);
        } else {
            prefix.append(propertyPath.getName());
        }
        EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(this.domainClass);
        String propertyName = resolveColumn(meta, decapitalize);
        if (null != propertyName) {
            return buildPredicatePart(predicatePart, propertyName);
        }

        // not found, then append type string
        if (null != predicatePart.getSourceSuffix()) {
            String candidateField = prefix + predicatePart.getSourceSuffix();
            propertyName = resolveColumn(meta, candidateField);
            if (null != propertyName) {
                return buildPredicatePart(predicatePart, propertyName);
            }
        }
        // 找下一个
        if (loop.incrementAndGet() < list.size()) {
            prefix.append("And");
            return findPart(loop, prefix, list);
        }
        return null;
    }

    private static PredicatePart buildPredicatePart(PredicatePart original, String propertyName) {
        PropertyPath path = new PropertyPath(propertyName, original.getPropertyPath().getOwner());
        PredicatePart copy = new PredicatePart(path, original.getMethodPredicateType());
        copy.setOrPartEnd(original.isOrPartEnd());
        copy.setSourceSuffix(original.getSourceSuffix());
        return copy;
    }

    @Override
    public int lastIndex() {
        return 0;
    }


    private static class Subject {

        private static final String DISTINCT = "Distinct";
        private static final Pattern COUNT_BY_TEMPLATE = Pattern.compile("^count(\\p{Lu}.*?)??By");
        private static final Pattern EXISTS_BY_TEMPLATE = Pattern.compile("^(" + EXISTS_PATTERN + ")(\\p{Lu}.*?)??By");
        private static final Pattern DELETE_BY_TEMPLATE = Pattern.compile("^(" + DELETE_PATTERN + ")(\\p{Lu}.*?)??By");
        private static final Pattern UPDATE_BY_TEMPLATE = Pattern.compile("^(" + UPDATE_PATTERN + ")(\\p{Lu}.*?)??By");
        private static final String LIMITING_QUERY_PATTERN = "(First|Top)(\\d*)?";
        private static final Pattern LIMITED_QUERY_TEMPLATE = Pattern
                .compile("^(" + QUERY_PATTERN + ")(" + DISTINCT + ")?" + LIMITING_QUERY_PATTERN + "(\\p{Lu}.*?)??By");

        private final boolean distinct;
        private final boolean count;
        private final boolean exists;
        private final boolean delete;
        private final boolean update;
        private final String updateBody;
        private final Optional<Integer> maxResults;

        public Subject(Optional<String> subject) {
            this.distinct = subject.map(it -> it.contains(DISTINCT)).orElse(false);
            this.count = matches(subject, COUNT_BY_TEMPLATE);
            this.exists = matches(subject, EXISTS_BY_TEMPLATE);
            this.delete = matches(subject, DELETE_BY_TEMPLATE);
            this.maxResults = returnMaxResultsIfFirstKSubjectOrNull(subject);
            this.update = matches(subject, UPDATE_BY_TEMPLATE);
            this.updateBody = extractUpdateBody(subject);
        }

        private String extractUpdateBody(Optional<String> subject) {
            if (subject.isEmpty()) {
                return "";
            }
            Matcher m = UPDATE_BY_TEMPLATE.matcher(subject.get());
            if (!m.find()) {
                return "";
            }
            String grp = m.group(2);
            return grp == null ? "" : grp;
        }

        private Optional<Integer> returnMaxResultsIfFirstKSubjectOrNull(Optional<String> subject) {
            return subject.map(it -> {
                Matcher grp = LIMITED_QUERY_TEMPLATE.matcher(it);
                if (!grp.find()) {
                    return null;
                }
                return Strings.hasText(grp.group(4)) ? Integer.valueOf(grp.group(4)) : 1;
            });
        }

        public boolean isDelete() {
            return delete;
        }

        public boolean isUpdate() {
            return update;
        }

        public String getUpdateBody() {
            return updateBody;
        }

        public boolean isCountProjection() {
            return count;
        }

        public boolean isExistsProjection() {
            return exists;
        }

        public boolean isDistinct() {
            return distinct;
        }

        public Optional<Integer> getMaxResults() {
            return maxResults;
        }

        private boolean matches(Optional<String> subject, Pattern pattern) {
            return subject.map(it -> pattern.matcher(it).find()).orElse(false);
        }
    }

    private static class Predicate implements Iterable<OrPart> {

        private final List<OrPart> nodes;

        private Predicate(String pathToUse, Class<?> domainClass) {
            if (pathToUse == null || pathToUse.isBlank()) {
                this.nodes = List.of();
            } else {
                Pattern orPattern = Pattern.compile(String.format(KEYWORD_TEMPLATE, "Or"));
                String[] ors = orPattern.split(pathToUse);
                this.nodes = Arrays.stream(ors)
                        .filter(Strings::hasText)
                        .map(part -> new OrPart(part, domainClass))
                        .collect(Collectors.toList());
            }
        }

        public List<OrPart> nodes() {
            return nodes;
        }

        @NonNull
        @Override
        public Iterator<OrPart> iterator() {
            return nodes.iterator();
        }
    }
}
