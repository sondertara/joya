package com.sondertara.joya.core.jdbc.dao.support;


import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.Lists;
import com.sondertara.common.collection.Sets;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.cache.AliasCacheHelper;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.statment.creator.DeleteSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.SelectSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.SqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.UpdateSqlByClassCreator;
import com.sondertara.joya.core.jdbc.statment.creator.UpdateSqlCreator;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.model.WherePart;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.NativeSqlQueryConfiguration;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.parser.WherePartParser;
import com.sondertara.joya.exceptions.IllegalSqlException;
import org.jspecify.annotations.NonNull;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
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
    private static final String UPDATE_PATTERN = "count";
    private static final String EXISTS_PATTERN = "exists";
    private static final String DELETE_PATTERN = "delete|remove";
    private static final Pattern PREFIX_TEMPLATE = Pattern.compile(
            "^(" + QUERY_PATTERN + "|" + COUNT_PATTERN + "|" + EXISTS_PATTERN + "|" + DELETE_PATTERN + ")((\\p{Lu}.*?))??By");


    private Subject subject;
    private Predicate predicate;


    public JPAQueryParser(Class<T> domainClass, Method method) {
        super(domainClass, method);
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

        StringJoiner sj = new StringJoiner(" ");
        StringBuilder candidate = new StringBuilder();
        AtomicInteger loop = new AtomicInteger(0);

        List<PredicatePart> parts = new ArrayList<>();

        WhereCondition<T> whereCriterion = new WhereCondition<>(LinkType.OR);

        List<OrPart> list = this.predicate.toList();
        for (int j = 0; j < list.size(); j++) {
            OrPart part = list.get(j);
            boolean needVisitNext = false;
            for (int i = 0; i < part.getChildren().size(); i++) {
                loop.set(i);
                PredicatePart predicatePart = findPart(loop, candidate, part.getChildren());
                //find
                if (null != predicatePart) {
                    //is Last one append or
                    if (loop.get() == part.getChildren().size() - 1) {
                        predicatePart.setOrPartEnd(true);

                    }
                    parts.add(predicatePart);
                    candidate.delete(0, candidate.length());
                    if (loop.get() > i) {
                        i = loop.get();
                    }
                } else {
                    //没找到说明Or将正常字段分隔，找下一个
                    needVisitNext = true;
                    candidate.append("Or");
                    break;
                }
            }

            if (needVisitNext) {
                continue;
            }

            if (j != list.size() - 1) {
                sj.add("Or");
            }
        }

        if (candidate.length() > 0) {
            throw new IllegalSqlException("No property name found by name({}) for the method({}),please check it", candidate, "fff");
        }

        StringJoiner stringJoiner = new StringJoiner(" ");


        List<PredicatePart> andPart = new ArrayList<>();

        AtomicInteger paramIndex = new AtomicInteger();

        for (int i = 0; i < parts.size(); i++) {
            PredicatePart part = parts.get(i);
            String columnName = part.getPropertyPath().getName();
            stringJoiner.add(columnName);
            if (i < parts.size() - 1) {
                stringJoiner.add(part.isOrPartEnd() ? "OR" : "AND");
            }

            andPart.add(part);
            //or子查询结束
            if (part.isOrPartEnd()) {
                whereCriterion.and(condition -> {
                    for (PredicatePart predicatePart : andPart) {
                        MethodPredicateType methodPredicateType = predicatePart.getMethodPredicateType();
                        String name = predicatePart.getPropertyPath().getName();

                        int lastIndex = paramIndex.addAndGet(methodPredicateType.getNumberOfArguments());
                        switch (methodPredicateType) {
                            case STARTING_WITH:
                                whereCriterion.startsWith(name, null);
                                break;
                            case IS_NULL:
                                whereCriterion.isNull(name);
                                break;
                            case IS_NOT_NULL:
                                whereCriterion.isNotNull(name);
                                break;
                            case NOT_LIKE:
                                whereCriterion.notContains(name, null);
                                break;
                            case LIKE:
                                whereCriterion.contains(name, "");
                                break;
                            case IN:
                                whereCriterion.in(name, Lists.newArrayList());
                                break;
                            case IS_NOT_EMPTY:

                            case WITHIN:

                            case NOT_IN:
                                whereCriterion.notIn(name, Lists.newArrayList("dual"));
                                break;
                            case BETWEEN:
                                whereCriterion.between(name, Lists.newArrayList("dual,dual"));
                                break;
                            case IS_EMPTY:
                            default:
                                whereCriterion.eq(name, "dual");
                        }
                    }
                    return whereCriterion;
                });
                andPart.clear();
            }


        }

        TableMetaContext context = TableMetaContext.of(domainClass);
        java.lang.reflect.Type returnType = statementMethod.getGenericReturnType();

        SqlCreator sqlCreator = null;
        if (subject.isDelete()) {
            DeleteSqlCreator<T> creator = new DeleteSqlCreator<>(context);
            creator.setWhereCriterion(whereCriterion);
            sqlCreator = creator;
        } else if (subject.update) {
            UpdateSqlCreator<T> creator = new UpdateSqlByClassCreator<>(context);
            creator.setWhereCondition(whereCriterion);
            sqlCreator = creator;
        } else if (subject.exists) {
            SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
            creator.setDeclaredColumns(Sets.newHashSet("count(*)"));
            creator.setWhereCriterion(whereCriterion);
            sqlCreator = creator;
        } else {
            SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
            creator.setWhereCriterion(whereCriterion);
            sqlCreator = creator;
        }

        return new DefaultEntityBasedBoundSql(returnType, sqlCreator.create(), new ArrayList<>());
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
        EntityTableMetaDefinition metaDefinition = CacheHolder.tableMetaCache().findByEntityClass(this.domainClass).get();
        // find field
        String propertyName = metaDefinition.findColumnNameByField(decapitalize);
        if (null != propertyName) {
            return buildPredicatePart(predicatePart, propertyName);
        }

        // not found,then append type string
        if (null != predicatePart.getSourceSuffix()) {
            propertyName = metaDefinition.findColumnNameByField(prefix + predicatePart.getSourceSuffix());
            if (null != propertyName) {
                return buildPredicatePart(predicatePart, propertyName);
            }
        }
        //找下一个
        if (loop.incrementAndGet() < list.size()) {
            prefix.append("And");
            return findPart(loop, prefix, list);
        }
        return null;
    }

    private static PredicatePart buildPredicatePart(PredicatePart original, String propertyName) {

        PropertyPath path = new PropertyPath(propertyName, original.getPropertyPath().getOwner());
        return new PredicatePart(path, original.getMethodPredicateType());
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

                return StringUtils.hasText(grp.group(4)) ? Integer.valueOf(grp.group(4)) : 1;
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
            String[] ors = pathToUse.split("Or");

            this.nodes = Arrays.stream(ors).map(part -> new OrPart(part, domainClass)).collect(Collectors.toList());

        }


        @NonNull
        @Override
        public Iterator<OrPart> iterator() {
            return nodes.iterator();
        }
    }
}
