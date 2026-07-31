package com.sondertara.joya.core.query.criterion;


import com.sondertara.common.classpath.ClassLoaderUtils;
import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import lombok.Getter;

import javax.persistence.EntityNotFoundException;
import java.lang.invoke.SerializedLambda;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.sondertara.joya.core.cache.AliasCacheHelper.GET_PATTERN;
import static com.sondertara.joya.core.cache.AliasCacheHelper.IS_PATTERN;

/**
 * where or having condition
 *
 * @author huangxiaohu
 */
public class WhereCondition<T> implements DeclaredQuery {
    private static final String POSITIONAL_OR_INDEXED_PARAMETER = "\\?(\\d+(?![#\\w]))";

    /**
     * where condition segments
     */
    private final StringJoiner segments;
    /**
     * params
     */
    @Getter
    private final List<Pair<String, Object>> params;
    /**
     * 占位符计数器 ?1 ?2 ?3
     */
    private int counts = 1;
    /**
     * the link type for where segments
     */
    private LinkType currentOpt;


    /**
     * 默认用 and 连接 查询条件
     */
    public WhereCondition() {
        this.currentOpt = LinkType.AND;
        this.segments = new StringJoiner(" " + currentOpt.name() + " ");
        this.params = new ArrayList<>();
    }

    public WhereCondition(LinkType linkType) {
        this.currentOpt = linkType;
        this.segments = new StringJoiner(" " + currentOpt.name() + " ");
        this.params = new ArrayList<>();
    }


    /**
     * where 条件 begin
     */
    public WhereCondition<T> eq(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} = ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * where 条件 begin
     */
    public WhereCondition<T> eq(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} = ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, value));
        }
        return this;

    }

    public WhereCondition<T> or() {
        return this;
    }

    public WhereCondition<T> and(UnaryOperator<WhereCondition<T>> and) {
        WhereCondition<T> criterion = getWhereCriterion(and, LinkType.AND);
        this.params.addAll(criterion.getParams());
        this.segments.add(criterion.segments.toString());
        return this;
    }

    public WhereCondition<T> or(UnaryOperator<WhereCondition<T>> or) {
        WhereCondition<T> criterion = getWhereCriterion(or, LinkType.OR);
        this.params.addAll(criterion.getParams());
        this.segments.add(criterion.segments.toString());
        return this;
    }


    private WhereCondition<T> getWhereCriterion(UnaryOperator<WhereCondition<T>> func, LinkType linkType) {
        WhereCondition<T> criterion = new WhereCondition<>(linkType);
        criterion.counts = this.counts;
        WhereCondition<T> apply = func.apply(criterion);
        StringJoiner joiner = apply.segments;

        if (Objects.isNull(joiner)) {
            throw new RuntimeException("sub query is null!");
        }
        return criterion;
    }

    /**
     * 不等于
     *
     * @param columnName column column
     * @param value      value      value
     * @return where criterion
     */
    public WhereCondition<T> ne(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} != ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 小于
     *
     * @param fn    column    列名
     * @param value value 值
     * @return where criterion
     */
    public WhereCondition<T> lt(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String columnName = getColumnName(fn);
            segments.add(StringUtils.format("{} < ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 小于
     *
     * @param columnName column 列名
     * @param value      value      值
     * @return where criterion
     */
    public WhereCondition<T> lt(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} < ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 小于等于
     *
     * @param fn    column    列名
     * @param value value value
     * @return where criterion
     */
    public WhereCondition<T> lte(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} <= ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, value));
        }
        return this;
    }

    private String getColumnName(IGetter<T, ?> fn) {
        String name = LambdaUtils.getImplMethodName(fn);
        EntityTableMetaDefinition metaDefinition = findTableMetaDefinition(fn);
        // get the field name
        if (GET_PATTERN.matcher(name).matches()) {
            name = name.substring(3);
        } else if (IS_PATTERN.matcher(name).matches()) {
            name = name.substring(2);
        }
        return metaDefinition.findColumnNameByField(name);
    }

    static <T> EntityTableMetaDefinition findTableMetaDefinition(IGetter<T, ?> taraSqlFn) {
        SerializedLambda serializedLambda = LambdaUtils.resolve(taraSqlFn);
        String implClass = serializedLambda.getImplClass();
        String getter = serializedLambda.getImplMethodName();

        String className = implClass.replace("/", ".");

        Optional<EntityTableMetaDefinition> optional = CacheHolder.tableMetaCache().findByEntityClass(ClassLoaderUtils.loadClass(className));
        return optional.orElseThrow(() -> new EntityNotFoundException("no entity found for " + implClass));
    }


    /**
     * 小于等于
     *
     * @param columnName column column
     * @param value      value      value
     * @return where criterion
     */
    public WhereCondition<T> lte(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} <= ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 大于
     *
     * @param fn    column    列名
     * @param value value value
     * @return where criterion
     */
    public WhereCondition<T> gt(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String columnName = getColumnName(fn);
            segments.add(StringUtils.format("{} > ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 大于
     *
     * @param columnName column column
     * @param value      value value
     * @return where criterion
     */
    public WhereCondition<T> gt(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} > ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * 大于等于
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> gte(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} >= ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, value));
        }
        return this;
    }

    /**
     * 大于等于
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> gte(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} >= ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value));
        }
        return this;
    }

    /**
     * is null
     *
     * @param fn column
     * @return where criterion
     */
    public WhereCondition<T> isNull(IGetter<T, ?> fn) {
        String tableColumn = getColumnName(fn);
        segments.add(tableColumn + " IS NULL");
        return this;
    }

    /**
     * is null
     *
     * @param columnName column
     * @return where criterion
     */
    public WhereCondition<T> isNull(String columnName) {
        segments.add(columnName + " IS NULL");
        return this;
    }

    /**
     * is not null
     *
     * @param fn column
     * @return where criterion
     */
    public WhereCondition<T> isNotNull(IGetter<T, ?> fn) {
        String tableColumn = getColumnName(fn);

        segments.add(tableColumn + " IS NOT NULL");
        return this;
    }

    /**
     * is not null
     *
     * @param columnName column
     * @return where criterion
     */
    public WhereCondition<T> isNotNull(String columnName) {
        segments.add(columnName + " IS NOT NULL");
        return this;
    }

    /**
     * right like
     *
     * @param fn    column    column name
     * @param value value filed
     * @return where criterion
     */
    public WhereCondition<T> startsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, value + "%"));
        }
        return this;
    }

    /**
     * right like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> startsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {

            segments.add(StringUtils.format("{} LIKE ?{}", counts, counts++));
            params.add(Pair.of(columnName, value + "%"));
        }
        return this;
    }

    /**
     * like
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> contains(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, "%" + value + "%"));
        }
        return this;
    }

    /**
     * like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> contains(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} LIKE ?{}", columnName, counts++));
            params.add(Pair.of(columnName, "%" + value + "%"));
        }
        return this;
    }

    /**
     * left like
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> endsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, "%" + value));
        }
        return this;
    }

    /**
     * left like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> endsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} LIKE ?{}", columnName, counts++));
            params.add(Pair.of(columnName, "%" + value));
        }
        return this;
    }

    /**
     * not  right like
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> notStartsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, value + "%"));
        }
        return this;
    }

    /**
     * not  right like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> notStartsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} NOT LIKE ?{}", columnName, counts++));
            params.add(Pair.of(columnName, value + "%"));
        }
        return this;
    }

    /**
     * not like
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> notContains(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, "%" + value + "%"));
        }
        return this;
    }

    /**
     * not like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> notContains(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} NOT LIKE ?{}", columnName, counts++));
            params.add(Pair.of(columnName, "%" + value + "%"));
        }
        return this;
    }

    /**
     * not left like
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */

    public WhereCondition<T> notEndsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            segments.add(StringUtils.format("{} NOT LIKE ?{}", columnName, counts++));
            params.add(Pair.of(columnName, "%" + value));
        }
        return this;
    }

    /**
     * not left like
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */

    public WhereCondition<T> notEndsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, counts++));
            params.add(Pair.of(tableColumn, "%" + value));
        }
        return this;
    }

    /**
     * in
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> in(String columnName, Collection<?> value) {
        if (Objects.nonNull(value)) {
            String arrayParamSql = buildArrayParamSql(columnName, value);
            segments.add(StringUtils.format("{} IN ({})", columnName, arrayParamSql));

        }
        return this;
    }

    /**
     * in
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> in(IGetter<T, ?> fn, Collection<?> value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            String arrayParamSql = buildArrayParamSql(tableColumn, value);
            segments.add(StringUtils.format("{} IN ({})", tableColumn, arrayParamSql));
        }
        return this;
    }

    private String buildArrayParamSql(String columnName, Collection<?> values) {
        final StringJoiner sj = new StringJoiner(", ");
        List<Pair<String, Object>> pairs = values.stream().map(obj -> {
            sj.add("?" + counts++);
            return new Pair<String, Object>(columnName, obj);
        }).collect(Collectors.toList());
        params.addAll(pairs);
        return sj.toString();
    }

    /**
     * not in
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public WhereCondition<T> notIn(String columnName, Collection<?> value) {
        if (Objects.nonNull(value)) {
            String arrayParamSql = buildArrayParamSql(columnName, value);
            segments.add(StringUtils.format("{} NOT IN ({})", columnName, arrayParamSql));
        }
        return this;
    }

    /**
     * not in
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public WhereCondition<T> notIn(IGetter<T, ?> fn, Collection<Object> value) {
        if (Objects.nonNull(value)) {
            String tableColumn = getColumnName(fn);
            String arrayParamSql = buildArrayParamSql(tableColumn, value);
            segments.add(StringUtils.format("{} NOT IN ({})", tableColumn, arrayParamSql));
            params.add(Pair.of(tableColumn, value));
        }
        return this;
    }

    /**
     * between
     *
     * @param columnName column
     * @param values     values
     * @return where criterion
     */
    public WhereCondition<T> between(String columnName, List<Object> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            segments.add(StringUtils.format("{} BETWEEN ?{} AND ?{}", columnName, counts++, counts++));
            params.add(Pair.of(columnName, values.get(0)));
            params.add(Pair.of(columnName, values.get(1)));
        }
        return this;
    }

    /**
     * between
     *
     * @param fn     column
     * @param values values
     * @return where criterion
     */
    public WhereCondition<T> between(IGetter<T, ?> fn, List<Object> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            String tableColumn = getColumnName(fn);
            segments.add(StringUtils.format("{} BETWEEN ?{} AND ?{}", tableColumn, counts++, counts++));
            params.add(Pair.of(tableColumn, values.get(0)));
            params.add(Pair.of(tableColumn, values.get(1)));
        }
        return this;
    }

    /**
     * not between
     *
     * @param columnName column
     * @param values     values
     * @return where criterion
     */
    public WhereCondition<T> notBetween(String columnName, List<Object> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            segments.add(StringUtils.format("{} NOT BETWEEN ?{} AND ?{}", columnName, counts++, counts++));
            params.add(Pair.of(columnName, values.get(0)));
            params.add(Pair.of(columnName, values.get(1)));
        }
        return this;
    }

    /**
     * not between
     *
     * @param fn     column
     * @param values values
     * @return where criterion
     */
    public WhereCondition<T> notBetween(IGetter<T, ?> fn, List<Object> values) {
        if (null == values || values.size() != 2) {
            throw new IllegalArgumentException("between params count must be 2");
        }
        String tableColumn = getColumnName(fn);
        segments.add(StringUtils.format("{} NOT BETWEEN ?{} AND ?{}", tableColumn, counts++, counts++));
        params.add(Pair.of(tableColumn, values.get(0)));
        params.add(Pair.of(tableColumn, values.get(1)));
        return this;
    }

    /**
     * 追加到 where条件中自定义sql字符串片段
     */
    public void addCondition(String part) {
        this.segments.add(SqlFormatUtils.underlineColumn(part));
    }

    public int getCounts() {
        return counts - 1;
    }


    @Override
    public String getQueryString() {
        if (usesJpaStyleParameters()) {
            return this.segments.toString().replaceAll(POSITIONAL_OR_INDEXED_PARAMETER, "?");
        }
        return this.segments.toString();
    }

    @Override
    public boolean hasParams() {
        return this.params.size() > 0 && this.counts > 0;
    }

    @Override
    public boolean usesJpaStyleParameters() {
        return SqlFormatUtils.NUMBERED_STYLE_PARAM.matcher(this.segments.toString()).find();
    }

    public enum Operator {
        /**
         *
         */
        EQ, NE, LT, LTE, GT, GTE, IS_NULL, IS_NOT_NULL, IS_EMPTY, IS_NOT_EMPTY, LIKE, NOT_LIKE, IN, NOT_IN, BETWEEN, NOT_BETWEEN, AND, OR
    }


}
