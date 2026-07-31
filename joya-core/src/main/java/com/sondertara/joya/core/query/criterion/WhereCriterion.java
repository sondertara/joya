package com.sondertara.joya.core.query.criterion;


import com.sondertara.common.struct.Pair;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.cache.AliasCacheHelper;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.core.model.AndQueryCondition;
import com.sondertara.joya.core.model.BetweenCondition;
import com.sondertara.joya.core.model.Condition;
import com.sondertara.joya.core.model.OrQueryCondition;
import com.sondertara.joya.core.model.RawStringCondition;
import com.sondertara.joya.core.model.WhereConditionVisitor;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.utils.SqlFormatUtils;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

/**
 * where or having condition
 *
 * @author huangxiaohu
 */
public class WhereCriterion {

    private final List<Pair<Operator, WhereCriterion>> subPart = new ArrayList<>();
    /**
     * the link type for where segments
     */
    private final LinkType currentOpt;
    /**
     * where condition segments
     */
    private StringJoiner segments;
    /**
     * params
     */
    private List<Object> params;

    @Getter
    private final List<WhereConditionVisitor> conditions = new ArrayList<>();
    /**
     * 占位符计数器 ?1 ?2 ?3
     */
    private int counts = 1;

    /**
     * 默认用 and 连接 查询条件
     */

    private final AliasCacheHelper aliasCacheHelper;

    public WhereCriterion(AliasCacheHelper aliasCacheHelper) {
        this(LinkType.AND, aliasCacheHelper);
    }

    /**
     * 指定查询条件之间是用 and 还是 or 连接
     *
     * @param linkType         Operator.AND/Operator.OR
     * @param aliasCacheHelper
     */
    public WhereCriterion(LinkType linkType, AliasCacheHelper aliasCacheHelper) {
        this.currentOpt = linkType;
        this.segments = new StringJoiner(" " + linkType.name() + " ");
        this.aliasCacheHelper = aliasCacheHelper;
        this.params = new ArrayList<>();
    }


    public WhereCriterion and(UnaryOperator<WhereCriterion> and) {
        WhereCriterion criterion = getWhereCriterion(and, LinkType.AND);
        this.params.addAll(criterion.getParams());
        this.segments.add(criterion.getSegments().toString());
        this.conditions.add(new AndQueryCondition(criterion.getConditions(), false));
        return this;
    }

    public WhereCriterion andNested(UnaryOperator<WhereCriterion> and) {
        WhereCriterion criterion = getWhereCriterion(and, LinkType.AND);
        this.params.addAll(criterion.getParams());
        this.segments.add(StringUtils.format(" ( {} )", criterion.getSegments()));
        this.conditions.add(new AndQueryCondition(criterion.getConditions(), true));
        return this;
    }

    public WhereCriterion or(UnaryOperator<WhereCriterion> or) {
        WhereCriterion criterion = getWhereCriterion(or, LinkType.OR);
        this.params.addAll(criterion.getParams());
        this.segments.add(criterion.getSegments().toString());
        this.conditions.add(new OrQueryCondition(criterion.getConditions(), false));
        return this;
    }

    public WhereCriterion orNested(UnaryOperator<WhereCriterion> or) {
        WhereCriterion criterion = getWhereCriterion(or, LinkType.OR);
        this.params.addAll(criterion.getParams());
        this.segments.add(StringUtils.format("( {} )", criterion.getSegments()));

        this.conditions.add(new OrQueryCondition(criterion.getConditions(), true));
        return this;
    }

    @NonNull
    private WhereCriterion getWhereCriterion(UnaryOperator<WhereCriterion> func, LinkType operator) {
        WhereCriterion criterion = new WhereCriterion(operator, aliasCacheHelper);
        criterion.counts = this.counts;
        WhereCriterion apply = func.apply(criterion);
        StringJoiner joiner = apply.getSegments();

        if (Objects.isNull(joiner)) {
            throw new RuntimeException("sub query is null!");
        }
        return criterion;
    }


    /**
     * where 条件 begin
     */
    public <T> WhereCriterion eq(IGetter<T, ?> fn, Object value) {
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
        int index = counts++;
        segments.add(StringUtils.format("{} = ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value));
        return this;

    }

    /**
     * where 条件 begin
     */
    public WhereCriterion eq(String columnName, Object value) {
        String fieldName = this.aliasCacheHelper.getColumnName(columnName);
        segments.add(StringUtils.format("{} = ?{}", fieldName, counts++));
        params.add(value);
        conditions.add(new Condition(fieldName, value));
        return this;
    }

    /**
     * where 条件 begin
     */
    public <T, R> WhereCriterion eq(IGetter<T, ?> left, IGetter<R, ?> right) {
        if (null == left || null == right) {
            throw new TaraException("The params is required");
        }
        String tableColumnL = this.aliasCacheHelper.getColumn(left).identity();
        String tableColumnR = this.aliasCacheHelper.getColumn(right).identity();
        segments.add(StringUtils.format("{} = {}", tableColumnL, tableColumnR));
        conditions.add(new Condition(tableColumnL, tableColumnR));
        return this;
    }

    /**
     * 不等于
     *
     * @param fn    column column
     * @param value value      value
     * @return where criterion
     */
    public <T> WhereCriterion ne(IGetter<T, ?> fn, Object value) {
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
        int index = counts++;
        segments.add(StringUtils.format("{} != ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.NEQ));
        return this;
    }

    public <T, R> WhereCriterion ne(IGetter<T, ?> left, IGetter<R, ?> right) {
        if (null == left || null == right) {
            throw new TaraException("The params is required");
        }
        String tableColumnL = this.aliasCacheHelper.getColumn(left).identity();
        String tableColumnR = this.aliasCacheHelper.getColumn(right).identity();
        segments.add(StringUtils.format("{} = {}", tableColumnL, tableColumnR));
        conditions.add(new Condition(tableColumnL, tableColumnR, Operator.NEQ));
        return this;
    }

    /**
     * 不等于
     *
     * @param columnName column column
     * @param value      value      value
     * @return where criterion
     */
    public WhereCriterion ne(String columnName, Object value) {
        String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
        int index = counts++;
        segments.add(StringUtils.format("{} != ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.NEQ));
        return this;
    }

    /**
     * 小于
     *
     * @param fn    column    列名
     * @param value value 值
     * @return where criterion
     */
    public <T> WhereCriterion lt(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} < ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.LT));
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
    public WhereCriterion lt(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} < ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.LT));
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
    public <T> WhereCriterion lte(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {

            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} <= ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.LTE));
        }
        return this;
    }

    /**
     * 小于等于
     *
     * @param columnName column column
     * @param value      value      value
     * @return where criterion
     */
    public WhereCriterion lte(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} <= ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.LTE));
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
    public <T> WhereCriterion gt(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} > ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.GT));

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
    public WhereCriterion gt(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} > ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.GT));
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
    public <T> WhereCriterion gte(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} >= ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.GTE));
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
    public WhereCriterion gte(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} >= ?{}", tableColumn, index));
            params.add(value);
            conditions.add(new Condition(tableColumn, value, Operator.GTE));
        }
        return this;
    }

    /**
     * is null
     *
     * @param fn column
     * @return where criterion
     */
    public <T> WhereCriterion isNull(IGetter<T, ?> fn) {
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();

        segments.add(tableColumn + " IS NULL");
        conditions.add(new Condition(tableColumn, null, Operator.IS_NULL));
        return this;
    }

    /**
     * is null
     *
     * @param columnName column
     * @return where criterion
     */
    public WhereCriterion isNull(String columnName) {
        String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
        segments.add(tableColumn + " IS NULL");
        conditions.add(new Condition(tableColumn, null, Operator.IS_NULL));
        return this;
    }

    /**
     * is not null
     *
     * @param fn column
     * @return where criterion
     */
    public <T> WhereCriterion isNotNull(IGetter<T, ?> fn) {
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();

        segments.add(tableColumn + " IS NOT NULL");
        conditions.add(new Condition(tableColumn, null, Operator.IS_NOT_NULL));
        return this;
    }

    /**
     * is not null
     *
     * @param columnName column
     * @return where criterion
     */
    public WhereCriterion isNotNull(String columnName) {
        String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
        segments.add(tableColumn + " IS NOT NULL");
        conditions.add(new Condition(tableColumn, null, Operator.IS_NOT_NULL));
        return this;
    }

    /**
     * right like
     *
     * @param fn    column    column name
     * @param value value filed
     * @return where criterion
     */
    public <T> WhereCriterion startsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add(value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.LIKE_R));
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
    public WhereCriterion startsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add(value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.LIKE_R));
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
    public <T> WhereCriterion contains(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add("%" + value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.LIKE));
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
    public WhereCriterion contains(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add("%" + value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.LIKE));
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
    public <T> WhereCriterion endsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add("%" + value);
            conditions.add(new Condition(tableColumn, value, Operator.LIKE_L));
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
    public WhereCriterion endsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} LIKE ?{}", tableColumn, index));
            params.add("%" + value);
            conditions.add(new Condition(tableColumn, value, Operator.LIKE_L));
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
    public <T> WhereCriterion notStartsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add(value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE_R));
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
    public WhereCriterion notStartsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add(value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE_R));
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
    public <T> WhereCriterion notContains(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add("%" + value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE));
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
    public WhereCriterion notContains(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add("%" + value + "%");
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE));
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

    public WhereCriterion notEndsWith(String columnName, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add("%" + value);
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE_L));
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

    public <T> WhereCriterion notEndsWith(IGetter<T, ?> fn, Object value) {
        if (Objects.nonNull(value)) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            int index = counts++;
            segments.add(StringUtils.format("{} NOT LIKE ?{}", tableColumn, index));
            params.add("%" + value);
            conditions.add(new Condition(tableColumn, value, Operator.NOT_LIKE_L));
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
    public <R> WhereCriterion in(String columnName, Collection<R> value) {
        if (CollectionUtils.isEmpty(value)) {
            throw new IllegalArgumentException("The parameter is empty");
        }
        String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
        int index = counts++;
        segments.add(StringUtils.format("{} IN ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.IN));

        return this;
    }

    /**
     * in
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public <T, R> WhereCriterion in(IGetter<T, ?> fn, Collection<R> value) {
        if (CollectionUtils.isEmpty(value)) {
            throw new IllegalArgumentException("The parameter is empty");
        }
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
        int index = counts++;
        segments.add(StringUtils.format("{} IN ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.IN));
        return this;
    }

    /**
     * not in
     *
     * @param columnName column
     * @param value      value
     * @return where criterion
     */
    public <R> WhereCriterion notIn(String columnName, Collection<R> value) {
        if (CollectionUtils.isEmpty(value)) {
            throw new IllegalArgumentException("The parameter is empty");
        }
        String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
        int index = counts++;
        segments.add(StringUtils.format("{} NOT IN ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.NOT_IN));
        return this;
    }

    /**
     * not in
     *
     * @param fn    column
     * @param value value
     * @return where criterion
     */
    public <T, R> WhereCriterion notIn(IGetter<T, ?> fn, Collection<R> value) {
        if (CollectionUtils.isEmpty(value)) {
            throw new IllegalArgumentException("The parameter is empty");
        }
        String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
        int index = counts++;
        segments.add(StringUtils.format("{} NOT IN ?{}", tableColumn, index));
        params.add(value);
        conditions.add(new Condition(tableColumn, value, Operator.NOT_IN));
        return this;
    }

    /**
     * between
     *
     * @param columnName column
     * @param values     values
     * @return where criterion
     */
    public <R> WhereCriterion between(String columnName, List<R> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            int index = counts++;
            segments.add(StringUtils.format("{} BETWEEN ?{} AND ?{}", tableColumn, counts++, index));
            params.addAll(values);
            conditions.add(new BetweenCondition(tableColumn, values.get(0), values.get(1), true));
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
    public <T, R> WhereCriterion between(IGetter<T, ?> fn, List<R> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            segments.add(StringUtils.format("{} BETWEEN ?{} AND ?{}", tableColumn, counts++));
            params.addAll(values);
            conditions.add(new BetweenCondition(tableColumn, values.get(0), values.get(1), true));

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
    public <R> WhereCriterion notBetween(String columnName, List<R> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            String tableColumn = this.aliasCacheHelper.getColumnName(columnName);
            segments.add(StringUtils.format("{} NOT BETWEEN ?{} AND ?{}", tableColumn, counts++));
            params.addAll(values);
            conditions.add(new BetweenCondition(tableColumn, values.get(0), values.get(1), false));

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
    public <T, R> WhereCriterion notBetween(IGetter<T, ?> fn, List<R> values) {
        if (CollectionUtils.isNotEmpty(values) && values.size() == JoyaConst.TWO_QUERY_COUNT) {
            String tableColumn = this.aliasCacheHelper.getColumn(fn).identity();
            segments.add(StringUtils.format("{} NOT BETWEEN ?{} AND ?{}", tableColumn, counts++));
            params.addAll(values);
            conditions.add(new BetweenCondition(tableColumn, values.get(0), values.get(1), false));
        }
        return this;
    }

    /**
     * 追加到 where条件中自定义sql字符串片段
     */
    public WhereCriterion addCondition(String part, Object... params) {
        this.segments.add(SqlFormatUtils.underlineColumn(part));
        conditions.add(new RawStringCondition(part, params));
        return this;
    }

    public StringJoiner getSegments() {
        return this.segments;
    }

    public void setSegments(StringJoiner segments) {
        this.segments = segments;
    }

    public int getCounts() {
        return counts - 1;
    }

    public void setCounts(int counts) {
        this.counts = counts;
    }

    public List<Object> getParams() {
        return params;
    }


    public void setParams(List<Object> params) {
        this.params = params;
    }


}
