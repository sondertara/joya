package com.sondertara.joya.core.query.criterion;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.SqlPredicate;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.SqlFormatUtils;
import com.sondertara.joya.utils.Strings;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

/**
 * WHERE / HAVING 条件构建器。
 *
 * <p>核心设计：
 * <ul>
 *   <li>在 API 调用期仅将条件封装为持有 {@link ColumnMetaProvider} 的惰性 {@link SqlPredicate}，
 *       不触发 {@code t0 / t1} 表别名分配与字符串拼接；</li>
 *   <li>同一 {@link WhereCriterion} 实例既可在多表 {@code Query} 中渲染为带别名的条件（{@code t0.col = ?}），
 *       也可在单表 {@code EntityUpdate / EntityDelete} 中渲染为无别名的条件（{@code col = ?}）；</li>
 *   <li>运行期单趟直接向 {@link StringBuilder} 与 {@link SqlParameterList} 写入 JDBC {@code ?} 占位符及参数，
 *       无需 {@code ?1 ?2} 位置重编号。</li>
 * </ul>
 *
 * @author huangxiaohu
 * @date 2021/11/15 16:44
 * @since 1.0.0
 */
public class WhereCriterion {

    private static final int MAX_IN_SIZE = 1000;

    @Getter
    private final LinkType currentOpt;

    private final List<SqlPredicate> predicates = new ArrayList<>();

    public WhereCriterion() {
        this(LinkType.AND);
    }

    public WhereCriterion(LinkType linkType) {
        this.currentOpt = Objects.requireNonNull(linkType, "linkType must not be null");
    }

    /**
     * 静态工厂方法：创建一个空的 {@code AND} 条件构建器。
     */
    public static WhereCriterion create() {
        return new WhereCriterion(LinkType.AND);
    }

    /**
     * 兼容静态工厂方法：创建一个空的 {@code AND} 条件构建器（延迟绑定架构下无需预注册实体表别名）。
     */
    public static WhereCriterion forEntity(Class<?>... entityTypes) {
        return new WhereCriterion(LinkType.AND);
    }

    /**
     * 获取已收集的惰性谓词列表（只读视图）。
     */
    public List<SqlPredicate> getPredicates() {
        return Collections.unmodifiableList(predicates);
    }

    /**
     * 将当前条件构建器中的所有谓词渲染到目标 SQL 缓冲与参数列表中。
     */
    public void render(RenderContext ctx, StringBuilder sql, SqlParameterList params, ParameterSource source) {
        String delimiter = " " + currentOpt.name() + " ";
        boolean first = true;
        for (SqlPredicate predicate : predicates) {
            StringBuilder itemSql = new StringBuilder();
            predicate.render(ctx, itemSql, params, source);
            if (itemSql.isEmpty()) {
                continue;
            }
            if (!first) {
                sql.append(delimiter);
            }
            sql.append(itemSql);
            first = false;
        }
    }

    /**
     * 添加原生 SQL 条件片段（如 {@code "t0.age > ? AND t0.status = ?"}）。
     */
    public WhereCriterion addCondition(String part, Object... params) {
        if (Strings.isBlank(part)) {
            return this;
        }
        Object[] capturedParams = params == null ? new Object[0] : params.clone();
        predicates.add((ctx, sql, paramList, source) -> {
            String formatted = SqlFormatUtils.underlineColumn(part.trim());
            formatted = SqlFormatUtils.getJdbcStyleSql(formatted);
            if (!ctx.isAliased()) {
                formatted = SqlFormatUtils.stripTableAlias(formatted);
            }
            sql.append(formatted);
            for (Object param : capturedParams) {
                paramList.add(null, param, source);
            }
        });
        return this;
    }

    // ==================== 比较条件 ====================

    public <T> WhereCriterion eq(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " = ?", value);
    }

    public WhereCriterion eq(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " = ?", value);
    }

    public <T> WhereCriterion ne(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " <> ?", value);
    }

    public WhereCriterion ne(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " <> ?", value);
    }

    public <T, R> WhereCriterion ne(IGetter<T, ?> left, IGetter<R, ?> right) {
        if (Objects.isNull(left) || Objects.isNull(right)) {
            return this;
        }
        ColumnMetaProvider leftProvider = ColumnMetaProvider.fromGetter(left);
        ColumnMetaProvider rightProvider = ColumnMetaProvider.fromGetter(right);
        predicates.add((ctx, sql, params, source) ->
                sql.append(leftProvider.resolveSql(ctx))
                        .append(" != ")
                        .append(rightProvider.resolveSql(ctx)));
        return this;
    }

    public <T> WhereCriterion gt(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " > ?", value);
    }

    public WhereCriterion gt(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " > ?", value);
    }

    public <T> WhereCriterion gte(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " >= ?", value);
    }

    public WhereCriterion gte(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " >= ?", value);
    }

    public <T> WhereCriterion lt(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " < ?", value);
    }

    public WhereCriterion lt(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " < ?", value);
    }

    public <T> WhereCriterion lte(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " <= ?", value);
    }

    public WhereCriterion lte(String columnName, @Nullable Object value) {
        if (Objects.isNull(value)) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " <= ?", value);
    }

    // ==================== BETWEEN / NOT BETWEEN ====================

    public <T> WhereCriterion between(IGetter<T, ?> fn, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (Objects.isNull(leftValue) || Objects.isNull(rightValue)) {
            return this;
        }
        return addBetween(ColumnMetaProvider.fromGetter(fn), leftValue, rightValue, false);
    }

    public WhereCriterion between(String columnName, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (Objects.isNull(leftValue) || Objects.isNull(rightValue)) {
            return this;
        }
        return addBetween(ColumnMetaProvider.fromName(columnName), leftValue, rightValue, false);
    }

    public WhereCriterion between(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return addBetween(ColumnMetaProvider.fromName(columnName), list.get(0), list.get(1), false);
    }

    public <T> WhereCriterion between(IGetter<T, ?> fn, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return addBetween(ColumnMetaProvider.fromGetter(fn), list.get(0), list.get(1), false);
    }

    public <T> WhereCriterion notBetween(IGetter<T, ?> fn, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (Objects.isNull(leftValue) || Objects.isNull(rightValue)) {
            return this;
        }
        return addBetween(ColumnMetaProvider.fromGetter(fn), leftValue, rightValue, true);
    }

    public WhereCriterion notBetween(String columnName, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (Objects.isNull(leftValue) || Objects.isNull(rightValue)) {
            return this;
        }
        return addBetween(ColumnMetaProvider.fromName(columnName), leftValue, rightValue, true);
    }

    public WhereCriterion notBetween(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return addBetween(ColumnMetaProvider.fromName(columnName), list.get(0), list.get(1), true);
    }

    public <T> WhereCriterion notBetween(IGetter<T, ?> fn, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return addBetween(ColumnMetaProvider.fromGetter(fn), list.get(0), list.get(1), true);
    }

    // ==================== LIKE / NOT LIKE ====================

    public <T> WhereCriterion contains(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " LIKE ?", "%" + value + "%");
    }

    public WhereCriterion contains(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " LIKE ?", "%" + value + "%");
    }

    public <T> WhereCriterion startsWith(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " LIKE ?", value + "%");
    }

    public WhereCriterion startsWith(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " LIKE ?", value + "%");
    }

    public <T> WhereCriterion endsWith(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " LIKE ?", "%" + value);
    }

    public WhereCriterion endsWith(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " LIKE ?", "%" + value);
    }

    public <T> WhereCriterion notContains(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " NOT LIKE ?", "%" + value + "%");
    }

    public WhereCriterion notContains(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " NOT LIKE ?", "%" + value + "%");
    }

    public <T> WhereCriterion notStartsWith(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " NOT LIKE ?", value + "%");
    }

    public WhereCriterion notStartsWith(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " NOT LIKE ?", value + "%");
    }

    public <T> WhereCriterion notEndsWith(IGetter<T, ?> fn, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " NOT LIKE ?", "%" + value);
    }

    public WhereCriterion notEndsWith(String columnName, @Nullable Object value) {
        if (Objects.isNull(value) || Strings.isBlank(value.toString())) {
            return this;
        }
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " NOT LIKE ?", "%" + value);
    }

    // ==================== IN / NOT IN ====================

    public WhereCriterion in(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, false);
    }

    public <T> WhereCriterion in(IGetter<T, ?> fn, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromGetter(fn), values, false);
    }

    public WhereCriterion notIn(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, true);
    }

    public <T> WhereCriterion notIn(IGetter<T, ?> fn, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromGetter(fn), values, true);
    }

    // ==================== IS NULL / IS NOT NULL ====================

    public <T> WhereCriterion isNull(IGetter<T, ?> fn) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(fn);
        predicates.add((ctx, sql, params, source) -> sql.append(provider.resolveSql(ctx)).append(" IS NULL"));
        return this;
    }

    public WhereCriterion isNull(String columnName) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromName(columnName);
        predicates.add((ctx, sql, params, source) -> sql.append(provider.resolveSql(ctx)).append(" IS NULL"));
        return this;
    }

    public <T> WhereCriterion isNotNull(IGetter<T, ?> fn) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(fn);
        predicates.add((ctx, sql, params, source) -> sql.append(provider.resolveSql(ctx)).append(" IS NOT NULL"));
        return this;
    }

    public WhereCriterion isNotNull(String columnName) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromName(columnName);
        predicates.add((ctx, sql, params, source) -> sql.append(provider.resolveSql(ctx)).append(" IS NOT NULL"));
        return this;
    }

    // ==================== 组合与嵌套（AND / OR） ====================

    public WhereCriterion and(UnaryOperator<WhereCriterion> fn) {
        WhereCriterion child = fn.apply(new WhereCriterion(LinkType.AND));
        boolean wrap = this.currentOpt == LinkType.OR && child.predicates.size() > 1;
        return addChildCriterion(child, wrap);
    }

    public WhereCriterion or(UnaryOperator<WhereCriterion> fn) {
        WhereCriterion child = fn.apply(new WhereCriterion(LinkType.OR));
        boolean wrap = this.currentOpt == LinkType.AND && child.predicates.size() > 1;
        return addChildCriterion(child, wrap);
    }

    public WhereCriterion andNested(UnaryOperator<WhereCriterion> fn) {
        WhereCriterion child = fn.apply(new WhereCriterion(LinkType.AND));
        return addChildCriterion(child, true);
    }

    public WhereCriterion orNested(UnaryOperator<WhereCriterion> fn) {
        WhereCriterion child = fn.apply(new WhereCriterion(LinkType.OR));
        return addChildCriterion(child, true);
    }

    public boolean isEmpty() {
        return this.predicates.isEmpty();
    }

    // ==================== 内部辅助 ====================

    private WhereCriterion addBinaryComparison(ColumnMetaProvider provider, String operatorWithPlaceholder, Object value) {
        predicates.add((ctx, sql, params, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            sql.append(alias.identity()).append(operatorWithPlaceholder);
            params.add(alias.getColumnName(), value, source);
        });
        return this;
    }

    private WhereCriterion addBetween(ColumnMetaProvider provider, Object leftValue, Object rightValue, boolean notBetween) {
        String op = notBetween ? " NOT BETWEEN ? AND ?" : " BETWEEN ? AND ?";
        predicates.add((ctx, sql, params, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            sql.append(alias.identity()).append(op);
            params.add(alias.getColumnName(), leftValue, source);
            params.add(alias.getColumnName(), rightValue, source);
        });
        return this;
    }

    private WhereCriterion addInCondition(ColumnMetaProvider provider, Collection<?> values, boolean notIn) {
        if (values.size() > MAX_IN_SIZE) {
            throw new DbException("The size of in params cannot be greater than " + MAX_IN_SIZE);
        }
        List<Object> snapshot = new ArrayList<>(values);
        String prefix = notIn ? " NOT IN " : " IN ";
        predicates.add((ctx, sql, params, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (Object val : snapshot) {
                sj.add("?");
                params.add(alias.getColumnName(), val, source);
            }
            sql.append(alias.identity()).append(prefix).append(sj);
        });
        return this;
    }

    private WhereCriterion addChildCriterion(WhereCriterion child, boolean wrapParentheses) {
        if (child == null || child.isEmpty()) {
            return this;
        }
        predicates.add((ctx, sql, params, source) -> {
            StringBuilder childSql = new StringBuilder();
            child.render(ctx, childSql, params, source);
            if (childSql.isEmpty()) {
                return;
            }
            if (wrapParentheses) {
                sql.append('(').append(childSql).append(')');
            } else {
                sql.append(childSql);
            }
        });
        return this;
    }
}
