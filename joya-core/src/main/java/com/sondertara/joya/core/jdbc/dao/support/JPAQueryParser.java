package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.MutationContext;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
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

    private final @Nullable Object[] runtimeArgs;
    private Subject subject;
    private Predicate predicate;

    public JPAQueryParser(Class<T> domainClass, Method method) {
        this(domainClass, method, null);
    }

    public JPAQueryParser(Class<T> domainClass, Method method, @Nullable Object[] runtimeArgs) {
        super(domainClass, method);
        this.runtimeArgs = runtimeArgs;
    }

    @Override
    public EntityBasedBoundSql parse() {
        String methodName = statementMethod.getName();
        Matcher matcher = PREFIX_TEMPLATE.matcher(methodName);

        if (!matcher.find()) {
            this.subject = new Subject(Optional.empty());
            this.predicate = new Predicate(methodName, domainClass);
        } else {
            this.subject = new Subject(Optional.of(matcher.group(0)));
            this.predicate = new Predicate(methodName.substring(matcher.group().length()), domainClass);
        }

        StringBuilder candidate = new StringBuilder();
        AtomicInteger loop = new AtomicInteger(0);
        List<PredicatePart> parts = new ArrayList<>();

        List<OrPart> list = this.predicate.toList();
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

        List<JoyaExecutorSupport.ParameterBinding> bindings = new ArrayList<>();
        AtomicInteger argPosition = new AtomicInteger(0);

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

        EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(domainClass);
        java.lang.reflect.Type returnType = statementMethod.getGenericReturnType();

        String sql;
        if (subject.isDelete()) {
            MutationContext deleteCtx = new MutationContext(meta, Map.of(), whereCriterion, Set.of(), false, false);
            sql = DeleteStatementFactory.create(deleteCtx).sql();
        } else if (subject.update) {
            Set<String> allColumns = new LinkedHashSet<>(meta.columnNames());
            MutationContext updateCtx = new MutationContext(meta, Map.of(), whereCriterion, allColumns, false, false);
            sql = UpdateStatementFactory.create(updateCtx).sql();
        } else if (subject.isCountProjection() || subject.isExistsProjection()) {
            Set<String> countColumns = new LinkedHashSet<>();
            countColumns.add("count(*)");
            sql = SelectStatementFactory.create(meta, whereCriterion, countColumns).sql();
        } else {
            String extPart = subject.getMaxResults().map(limit -> "LIMIT " + limit).orElse(null);
            sql = SelectStatementFactory.create(meta, whereCriterion, null, extPart).sql();
        }

        return new DefaultEntityBasedBoundSql(returnType, sql, bindings);
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
        EntityTableMetaDefinition metaDefinition = CacheHolder.tableMetaCache().findByEntityClass(this.domainClass)
                .orElseThrow(() -> new IllegalStateException("The class is not table entity:" + domainClass));
        // find field
        String propertyName = metaDefinition.findColumnNameByField(decapitalize);
        if (null == propertyName && metaDefinition.containColumn(decapitalize)) {
            propertyName = decapitalize;
        }
        if (null != propertyName) {
            return buildPredicatePart(predicatePart, propertyName);
        }

        // not found, then append type string
        if (null != predicatePart.getSourceSuffix()) {
            String candidateField = prefix + predicatePart.getSourceSuffix();
            propertyName = metaDefinition.findColumnNameByField(candidateField);
            if (null == propertyName && metaDefinition.containColumn(candidateField)) {
                propertyName = candidateField;
            }
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
        private final Optional<Integer> maxResults;

        public Subject(Optional<String> subject) {

            this.distinct = subject.map(it -> it.contains(DISTINCT)).orElse(false);
            this.count = matches(subject, COUNT_BY_TEMPLATE);
            this.exists = matches(subject, EXISTS_BY_TEMPLATE);
            this.delete = matches(subject, DELETE_BY_TEMPLATE);
            this.maxResults = returnMaxResultsIfFirstKSubjectOrNull(subject);
            this.update = matches(subject, UPDATE_BY_TEMPLATE);
        }

        /**
         * @param subject
         * @return
         * @since 1.9
         */
        private Optional<Integer> returnMaxResultsIfFirstKSubjectOrNull(Optional<String> subject) {

            return subject.map(it -> {

                Matcher grp = LIMITED_QUERY_TEMPLATE.matcher(it);

                if (!grp.find()) {
                    return null;
                }

                return Strings.hasText(grp.group(4)) ? Integer.valueOf(grp.group(4)) : 1;
            });

        }

        /**
         * Returns {@literal true} if {@link Subject} matches {@link #DELETE_BY_TEMPLATE}.
         *
         * @return
         * @since 1.8
         */
        public boolean isDelete() {
            return delete;
        }

        public boolean isCountProjection() {
            return count;
        }

        /**
         * Returns {@literal true} if {@link Subject} matches {@link #EXISTS_BY_TEMPLATE}.
         *
         * @return
         * @since 1.13
         */
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

    private static class Predicate implements Streamable<OrPart> {

        private final String pathToUse;
        private final Class<?> domainClass;
        private final List<OrPart> nodes;

        private Predicate(String pathToUse, Class<?> domainClass) {
            this.pathToUse = pathToUse;
            this.domainClass = domainClass;
            Pattern orPattern = Pattern.compile(String.format(KEYWORD_TEMPLATE, "Or"));
            String[] ors = orPattern.split(pathToUse);

            this.nodes = Arrays.stream(ors).map(part -> new OrPart(part, domainClass)).collect(Collectors.toList());
        }


        @NonNull
        @Override
        public Iterator<OrPart> iterator() {
            return nodes.iterator();
        }
    }
}
