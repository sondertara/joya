package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.utils.CollectionUtils;
import com.sondertara.joya.core.function.IGetter;
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

    // ==================== 比较条件（严格非空：传 null 立即抛 IllegalArgumentException） ====================

    public <T> WhereCriterion eq(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " = ?", requireValue("eq", value));
    }

    public WhereCriterion eq(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " = ?", requireValue("eq", value));
    }

    public <T> WhereCriterion ne(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " <> ?", requireValue("ne", value));
    }

    public WhereCriterion ne(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " <> ?", requireValue("ne", value));
    }

    public <T, R> WhereCriterion ne(IGetter<T, ?> left, IGetter<R, ?> right) {
        Objects.requireNonNull(left, "left getter must not be null");
        Objects.requireNonNull(right, "right getter must not be null");
        ColumnMetaProvider leftProvider = ColumnMetaProvider.fromGetter(left);
        ColumnMetaProvider rightProvider = ColumnMetaProvider.fromGetter(right);
        predicates.add((ctx, sql, params, source) ->
                sql.append(leftProvider.resolveSql(ctx))
                        .append(" != ")
                        .append(rightProvider.resolveSql(ctx)));
        return this;
    }

    public <T> WhereCriterion gt(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " > ?", requireValue("gt", value));
    }

    public WhereCriterion gt(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " > ?", requireValue("gt", value));
    }

    public <T> WhereCriterion gte(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " >= ?", requireValue("gte", value));
    }

    public WhereCriterion gte(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " >= ?", requireValue("gte", value));
    }

    public <T> WhereCriterion lt(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " < ?", requireValue("lt", value));
    }

    public WhereCriterion lt(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " < ?", requireValue("lt", value));
    }

    public <T> WhereCriterion lte(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), " <= ?", requireValue("lte", value));
    }

    public WhereCriterion lte(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), " <= ?", requireValue("lte", value));
    }

    // ==================== 动态可选比较条件（值为 null / 空白 / 空集合时显式跳过） ====================

    public WhereCriterion when(boolean condition, UnaryOperator<WhereCriterion> block) {
        Objects.requireNonNull(block, "block must not be null");
        if (condition) {
            block.apply(this);
        }
        return this;
    }

    public <T> WhereCriterion eqIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : eq(fn, value);
    }

    public WhereCriterion eqIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : eq(columnName, value);
    }

    public <T> WhereCriterion neIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : ne(fn, value);
    }

    public WhereCriterion neIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : ne(columnName, value);
    }

    public <T> WhereCriterion gtIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : gt(fn, value);
    }

    public WhereCriterion gtIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : gt(columnName, value);
    }

    public <T> WhereCriterion gteIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : gte(fn, value);
    }

    public WhereCriterion gteIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : gte(columnName, value);
    }

    public <T> WhereCriterion ltIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : lt(fn, value);
    }

    public WhereCriterion ltIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : lt(columnName, value);
    }

    public <T> WhereCriterion lteIfPresent(IGetter<T, ?> fn, @Nullable Object value) {
        return value == null ? this : lte(fn, value);
    }

    public WhereCriterion lteIfPresent(String columnName, @Nullable Object value) {
        return value == null ? this : lte(columnName, value);
    }

    // ==================== BETWEEN / NOT BETWEEN ====================

    public <T> WhereCriterion between(IGetter<T, ?> fn, Object leftValue, Object rightValue) {
        return addBetween(
                ColumnMetaProvider.fromGetter(fn),
                requireValue("between(leftValue)", leftValue),
                requireValue("between(rightValue)", rightValue),
                false);
    }

    public WhereCriterion between(String columnName, Object leftValue, Object rightValue) {
        return addBetween(
                ColumnMetaProvider.fromName(columnName),
                requireValue("between(leftValue)", leftValue),
                requireValue("between(rightValue)", rightValue),
                false);
    }

    public WhereCriterion between(String columnName, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException("WhereCriterion.between values must not be null or empty");
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return between(columnName, list.get(0), list.get(1));
    }

    public <T> WhereCriterion between(IGetter<T, ?> fn, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException("WhereCriterion.between values must not be null or empty");
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return between(fn, list.get(0), list.get(1));
    }

    public <T> WhereCriterion betweenIfPresent(IGetter<T, ?> fn, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (leftValue == null || rightValue == null) {
            return this;
        }
        return between(fn, leftValue, rightValue);
    }

    public WhereCriterion betweenIfPresent(String columnName, @Nullable Object leftValue, @Nullable Object rightValue) {
        if (leftValue == null || rightValue == null) {
            return this;
        }
        return between(columnName, leftValue, rightValue);
    }

    public <T> WhereCriterion notBetween(IGetter<T, ?> fn, Object leftValue, Object rightValue) {
        return addBetween(
                ColumnMetaProvider.fromGetter(fn),
                requireValue("notBetween(leftValue)", leftValue),
                requireValue("notBetween(rightValue)", rightValue),
                true);
    }

    public WhereCriterion notBetween(String columnName, Object leftValue, Object rightValue) {
        return addBetween(
                ColumnMetaProvider.fromName(columnName),
                requireValue("notBetween(leftValue)", leftValue),
                requireValue("notBetween(rightValue)", rightValue),
                true);
    }

    public WhereCriterion notBetween(String columnName, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException("WhereCriterion.notBetween values must not be null or empty");
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return notBetween(columnName, list.get(0), list.get(1));
    }

    public <T> WhereCriterion notBetween(IGetter<T, ?> fn, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException("WhereCriterion.notBetween values must not be null or empty");
        }
        if (values.size() != 2) {
            throw new DbException("The size of between params must be 2");
        }
        List<Object> list = new ArrayList<>(values);
        return notBetween(fn, list.get(0), list.get(1));
    }

    // ==================== LIKE / NOT LIKE ====================

    public static String escapeLikeCharacters(Object value) {
        String raw = value.toString();
        StringBuilder sb = new StringBuilder(raw.length() + 4);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean containsLikeMetaChar(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                return true;
            }
        }
        return false;
    }

    private WhereCriterion addLikeComparison(
            ColumnMetaProvider col, boolean negated, boolean prefix, boolean suffix, Object rawValue) {
        String str = rawValue.toString();
        boolean escaped = containsLikeMetaChar(str);
        String body = escaped ? escapeLikeCharacters(str) : str;
        String pattern = (prefix ? "%" : "") + body + (suffix ? "%" : "");
        String op = (negated ? " NOT LIKE ?" : " LIKE ?") + (escaped ? " ESCAPE '\\'" : "");
        return addBinaryComparison(col, op, pattern);
    }

    public <T> WhereCriterion contains(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("contains", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), false, true, true, v);
    }

    public WhereCriterion contains(String columnName, Object value) {
        Object v = requireValue("contains", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), false, true, true, v);
    }

    public <T> WhereCriterion startsWith(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("startsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), false, false, true, v);
    }

    public WhereCriterion startsWith(String columnName, Object value) {
        Object v = requireValue("startsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), false, false, true, v);
    }

    public <T> WhereCriterion endsWith(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("endsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), false, true, false, v);
    }

    public WhereCriterion endsWith(String columnName, Object value) {
        Object v = requireValue("endsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), false, true, false, v);
    }

    public <T> WhereCriterion notContains(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("notContains", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), true, true, true, v);
    }

    public WhereCriterion notContains(String columnName, Object value) {
        Object v = requireValue("notContains", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), true, true, true, v);
    }

    public <T> WhereCriterion notStartsWith(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("notStartsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), true, false, true, v);
    }

    public WhereCriterion notStartsWith(String columnName, Object value) {
        Object v = requireValue("notStartsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), true, false, true, v);
    }

    public <T> WhereCriterion notEndsWith(IGetter<T, ?> fn, Object value) {
        Object v = requireValue("notEndsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromGetter(fn), true, true, false, v);
    }

    public WhereCriterion notEndsWith(String columnName, Object value) {
        Object v = requireValue("notEndsWith", value);
        return addLikeComparison(ColumnMetaProvider.fromName(columnName), true, true, false, v);
    }

    public <T> WhereCriterion containsIfNotBlank(IGetter<T, ?> fn, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return contains(fn, value);
    }

    public WhereCriterion containsIfNotBlank(String columnName, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return contains(columnName, value);
    }

    public <T> WhereCriterion startsWithIfNotBlank(IGetter<T, ?> fn, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return startsWith(fn, value);
    }

    public WhereCriterion startsWithIfNotBlank(String columnName, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return startsWith(columnName, value);
    }

    public <T> WhereCriterion endsWithIfNotBlank(IGetter<T, ?> fn, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return endsWith(fn, value);
    }

    public WhereCriterion endsWithIfNotBlank(String columnName, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return endsWith(columnName, value);
    }

    public <T> WhereCriterion notContainsIfNotBlank(IGetter<T, ?> fn, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return notContains(fn, value);
    }

    public WhereCriterion notContainsIfNotBlank(String columnName, @Nullable Object value) {
        if (value == null || Strings.isBlank(value.toString())) {
            return this;
        }
        return notContains(columnName, value);
    }

    // ==================== IN / NOT IN ====================

    public WhereCriterion in(String columnName, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.in values must not be null or empty (use inIfNotEmpty for optional filtering)");
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, false);
    }

    public <T> WhereCriterion in(IGetter<T, ?> fn, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.in values must not be null or empty (use inIfNotEmpty for optional filtering)");
        }
        return addInCondition(ColumnMetaProvider.fromGetter(fn), values, false);
    }

    public WhereCriterion notIn(String columnName, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.notIn values must not be null or empty (use notInIfNotEmpty for optional filtering)");
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, true);
    }

    public <T> WhereCriterion notIn(IGetter<T, ?> fn, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.notIn values must not be null or empty (use notInIfNotEmpty for optional filtering)");
        }
        return addInCondition(ColumnMetaProvider.fromGetter(fn), values, true);
    }

    public WhereCriterion inIfNotEmpty(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, false);
    }

    public <T> WhereCriterion inIfNotEmpty(IGetter<T, ?> fn, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromGetter(fn), values, false);
    }

    public WhereCriterion notInIfNotEmpty(String columnName, @Nullable Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            return this;
        }
        return addInCondition(ColumnMetaProvider.fromName(columnName), values, true);
    }

    public <T> WhereCriterion notInIfNotEmpty(IGetter<T, ?> fn, @Nullable Collection<?> values) {
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

    private static Object requireValue(String method, @Nullable Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "WhereCriterion." + method + " value must not be null (use isNull or " + method + "IfPresent)");
        }
        return value;
    }

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
        List<Object> snapshot = new ArrayList<>(values.size());
        for (Object val : values) {
            if (val == null) {
                throw new IllegalArgumentException("WhereCriterion." + (notIn ? "notIn" : "in")
                        + " collection must not contain null elements (use isNull or isNotNull explicitly)");
            }
            snapshot.add(val);
        }
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
