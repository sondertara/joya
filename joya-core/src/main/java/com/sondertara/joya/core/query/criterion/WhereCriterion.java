package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.utils.CollectionUtils;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.OnPredicate;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.SqlPredicate;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.utils.Strings;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
public class WhereCriterion implements OnPredicate {

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
     * 合并预构建的 {@link WhereCriterion} 与链式回调列表（全部为空时返回 {@code null}）。
     */
    public static @Nullable WhereCriterion merge(@Nullable WhereCriterion explicitCriterion,
                                                 List<UnaryOperator<WhereCriterion>> whereCallbacks) {
        if (explicitCriterion == null && (whereCallbacks == null || whereCallbacks.isEmpty())) {
            return null;
        }
        if (whereCallbacks == null || whereCallbacks.isEmpty()) {
            return explicitCriterion.copy();
        }
        WhereCriterion w = explicitCriterion != null
                ? explicitCriterion.copy()
                : WhereCriterion.create();
        for (UnaryOperator<WhereCriterion> cb : whereCallbacks) {
            cb.apply(w);
        }
        return w;
    }

    /**
     * 获取已收集的惰性谓词列表（只读视图）。
     */


    /**
     * 把当前条件树转换为主干与叶子均结构化的 {@link SqlNode}（仅调用方通过 {@link #addCondition}
     * 显式传入的原生 SQL 片段使用 {@link SqlNode.Raw} 承载）。
     */
    @Override
    public @Nullable SqlNode toNode(RenderContext ctx, ParameterSource source) {
        if (isEmpty()) {
            return null;
        }
        SqlNode result = null;
        for (SqlPredicate predicate : predicates) {
            SqlNode node = predicate.toNode(ctx, source);
            if (node == null) {
                continue;
            }
            result = result == null ? node : new SqlNode.Binary(currentOpt.name(), result, node);
        }
        return result;
    }

    /**
     * 添加原生 SQL 条件片段（如 {@code "t0.age > ? AND t0.status = ?"}）。
     */
    public WhereCriterion addCondition(String part, Object... params) {
        if (Strings.isBlank(part)) {
            return this;
        }
        Object[] capturedParams = params == null ? new Object[0] : params.clone();
        String formatted = SqlFormatUtils.getJdbcStyleSql(SqlFormatUtils.underlineColumn(part.trim()));
        int expected = SqlFormatUtils.countPlaceholders(formatted);
        if (expected != capturedParams.length) {
            throw new IllegalArgumentException("Placeholder count mismatch in addCondition(\"" + part
                    + "\"): expected " + expected + " parameter(s), got " + capturedParams.length);
        }
        predicates.add((ctx, source) -> {
            String rendered = formatted;
            if (!ctx.isAliased()) {
                rendered = SqlFormatUtils.stripTableAlias(rendered);
            }
            if (rendered.isEmpty()) {
                return null;
            }
            SqlParameterList paramList = new SqlParameterList();
            for (Object param : capturedParams) {
                paramList.add(null, param, source);
            }
            return SqlNodes.raw(rendered, paramList.toList());
        });
        return this;
    }

    // ==================== 比较条件（严格非空：传 null 立即抛 IllegalArgumentException） ====================

    public <T> WhereCriterion eq(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), "=", requireValue("eq", value));
    }

    public WhereCriterion eq(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), "=", requireValue("eq", value));
    }

    public <L, R> WhereCriterion eq(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("eq", left, right, "=");
    }

    public <T> WhereCriterion ne(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), "<>", requireValue("ne", value));
    }

    public WhereCriterion ne(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), "<>", requireValue("ne", value));
    }

    public <L, R> WhereCriterion ne(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("ne", left, right, "<>");
    }

    public <T> WhereCriterion gt(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), ">", requireValue("gt", value));
    }

    public WhereCriterion gt(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), ">", requireValue("gt", value));
    }

    public <L, R> WhereCriterion gt(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("gt", left, right, ">");
    }

    public <T> WhereCriterion gte(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), ">=", requireValue("gte", value));
    }

    public WhereCriterion gte(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), ">=", requireValue("gte", value));
    }

    public <L, R> WhereCriterion gte(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("gte", left, right, ">=");
    }

    public <T> WhereCriterion lt(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), "<", requireValue("lt", value));
    }

    public WhereCriterion lt(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), "<", requireValue("lt", value));
    }

    public <L, R> WhereCriterion lt(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("lt", left, right, "<");
    }

    public <T> WhereCriterion lte(IGetter<T, ?> fn, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromGetter(fn), "<=", requireValue("lte", value));
    }

    public WhereCriterion lte(String columnName, Object value) {
        return addBinaryComparison(ColumnMetaProvider.fromName(columnName), "<=", requireValue("lte", value));
    }

    public <L, R> WhereCriterion lte(IGetter<L, ?> left, IGetter<R, ?> right) {
        return addColumnComparison("lte", left, right, "<=");
    }

    private WhereCriterion addColumnComparison(String method, IGetter<?, ?> left, IGetter<?, ?> right, String operator) {
        if (left == null) {
            throw new IllegalArgumentException("WhereCriterion." + method + " left column must not be null");
        }
        requireValue(method, right);
        ColumnMetaProvider leftProvider = ColumnMetaProvider.fromGetter(left);
        ColumnMetaProvider rightProvider = ColumnMetaProvider.fromGetter(right);
        predicates.add((ctx, source) ->
                new SqlNode.Binary(operator, leftProvider.resolveNode(ctx), rightProvider.resolveNode(ctx)));
        return this;
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
        predicates.add((ctx, source) -> {
            ColumnAlias alias = col.resolveAlias(ctx);
            return new SqlNode.Like(
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()),
                    SqlNodes.param(alias.getColumnName(), pattern, source),
                    negated, escaped);
        });
        return this;
    }

    private WhereCriterion addRawLikeComparison(ColumnMetaProvider col, boolean negated, Object rawPattern) {
        String pattern = rawPattern.toString();
        predicates.add((ctx, source) -> {
            ColumnAlias alias = col.resolveAlias(ctx);
            return new SqlNode.Like(
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()),
                    SqlNodes.param(alias.getColumnName(), pattern, source),
                    negated, false);
        });
        return this;
    }

    /**
     * 原样模式 LIKE（不自动追加 {@code %}，也不转义调用方传入的 {@code %} / {@code _} 通配符）。
     */
    public <T> WhereCriterion like(IGetter<T, ?> fn, Object pattern) {
        Object v = requireValue("like", pattern);
        return addRawLikeComparison(ColumnMetaProvider.fromGetter(fn), false, v);
    }

    public WhereCriterion like(String columnName, Object pattern) {
        Object v = requireValue("like", pattern);
        return addRawLikeComparison(ColumnMetaProvider.fromName(columnName), false, v);
    }

    /**
     * 原样模式 NOT LIKE（不自动追加 {@code %}，也不转义调用方传入的 {@code %} / {@code _} 通配符）。
     */
    public <T> WhereCriterion notLike(IGetter<T, ?> fn, Object pattern) {
        Object v = requireValue("notLike", pattern);
        return addRawLikeComparison(ColumnMetaProvider.fromGetter(fn), true, v);
    }

    public WhereCriterion notLike(String columnName, Object pattern) {
        Object v = requireValue("notLike", pattern);
        return addRawLikeComparison(ColumnMetaProvider.fromName(columnName), true, v);
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

    /**
     * 可变参数 {@code IN} 条件（至少传入 1 个值）。
     */
    public WhereCriterion in(String columnName, Object first, Object... rest) {
        return in(columnName, toVarargsList("in", first, rest));
    }

    /**
     * 可变参数类型安全 {@code IN} 条件（至少传入 1 个值）。
     */
    @SafeVarargs
    public final <T, V> WhereCriterion in(IGetter<T, V> fn, V first, V... rest) {
        return in(fn, toVarargsList("in", first, rest));
    }

    /**
     * 可变参数 {@code NOT IN} 条件（至少传入 1 个值）。
     */
    public WhereCriterion notIn(String columnName, Object first, Object... rest) {
        return notIn(columnName, toVarargsList("notIn", first, rest));
    }

    /**
     * 可变参数类型安全 {@code NOT IN} 条件（至少传入 1 个值）。
     */
    @SafeVarargs
    public final <T, V> WhereCriterion notIn(IGetter<T, V> fn, V first, V... rest) {
        return notIn(fn, toVarargsList("notIn", first, rest));
    }

    /**
     * 显式分片 {@code IN} 条件：当集合元素数量超过 {@value #MAX_IN_SIZE} 时，自动按 {@value #MAX_IN_SIZE} 拆分为
     * {@code (col IN (...) OR col IN (...))} 括号子组，避免超出数据库 {@code IN} 列表上限。
     */
    public WhereCriterion inChunked(String columnName, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.inChunked values must not be null or empty (use inIfNotEmpty for optional filtering)");
        }
        return addChunkedInCondition(ColumnMetaProvider.fromName(columnName), values);
    }

    /**
     * 显式分片类型安全 {@code IN} 条件：当集合元素数量超过 {@value #MAX_IN_SIZE} 时，自动按 {@value #MAX_IN_SIZE} 拆分为
     * {@code (col IN (...) OR col IN (...))} 括号子组，避免超出数据库 {@code IN} 列表上限。
     */
    public <T> WhereCriterion inChunked(IGetter<T, ?> fn, Collection<?> values) {
        if (CollectionUtils.isEmpty(values)) {
            throw new IllegalArgumentException(
                    "WhereCriterion.inChunked values must not be null or empty (use inIfNotEmpty for optional filtering)");
        }
        return addChunkedInCondition(ColumnMetaProvider.fromGetter(fn), values);
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
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Unary("IS NULL",
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()));
        });
        return this;
    }

    public WhereCriterion isNull(String columnName) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromName(columnName);
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Unary("IS NULL",
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()));
        });
        return this;
    }

    public <T> WhereCriterion isNotNull(IGetter<T, ?> fn) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(fn);
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Unary("IS NOT NULL",
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()));
        });
        return this;
    }

    public WhereCriterion isNotNull(String columnName) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromName(columnName);
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Unary("IS NOT NULL",
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()));
        });
        return this;
    }

    // ==================== 组合与嵌套（AND / OR） ====================

    /**
     * 追加一个 AND 子条件组。
     *
     * <p>子组内部按子组自身的连接符渲染；子组与当前层已有条件之间使用当前层的连接符。
     * 当当前层为 OR 时，子组会被括号包裹以保证优先级。
     */
    public WhereCriterion and(UnaryOperator<WhereCriterion> fn) {
        return append(fn, LinkType.AND, false);
    }

    /**
     * 追加一个 OR 子条件组；子组与当前层已有条件之间仍使用当前层的连接符（通常为 AND）。
     *
     * <p>例如 {@code eq(active, true).or(c -> c.eq(deptId, 10).eq(age, 40))} 渲染为
     * {@code active = ? AND (dept_id = ? OR age = ?)}，而不是 {@code active = ? OR ...}。
     * 若需要 OR 与已有条件同级，请使用 {@code new WhereCriterion(LinkType.OR)} 作为根条件
     * （{@code Query.where(WhereCriterion)} 会为多谓词 OR 根自动加括号）。
     *
     * <p>当子组只有一个谓词时，追加结果与 {@link #and(UnaryOperator)} 等价。这通常意味着调用方
     * 误用了 OR 语义，因此直接抛出 {@link IllegalArgumentException}，避免静默返回错误结果。
     */
    public WhereCriterion or(UnaryOperator<WhereCriterion> fn) {
        Objects.requireNonNull(fn, "criterion function must not be null");
        WhereCriterion child = fn.apply(new WhereCriterion(LinkType.OR));
        if (child == null || child.isEmpty()) {
            return this;
        }
        if (!this.predicates.isEmpty() && child.predicates.size() == 1) {
            throw new IllegalArgumentException(
                    "WhereCriterion.or(...) appends an OR group which is combined with existing conditions by "
                            + this.currentOpt.name() + "; a single-predicate group is equivalent to and(...). "
                            + "Use anyOf(...) / andAnyOf(...) for dynamic OR groups, and(...) for an AND condition, "
                            + "orNested(...) for a parenthesized group, or build a WhereCriterion(LinkType.OR) root "
                            + "when a real OR with existing conditions is required.");
        }
        return appendChild(child, false);
    }

    /**
     * 追加一个“满足任一即可”的 OR 子条件组（语义为 {@code AND (cond1 OR cond2 ...)}）。
     *
     * <p>与 {@link #or(UnaryOperator)} 不同，本方法专为动态可选条件组合（如内部含多个 {@code *IfPresent}）设计：
     * 即使动态过滤后子组内仅剩 1 个谓词，也会正常追加而不会抛出异常；若子组为空则自动跳过。
     */
    public WhereCriterion anyOf(UnaryOperator<WhereCriterion> fn) {
        return append(fn, LinkType.OR, false);
    }

    /**
     * {@link #anyOf(UnaryOperator)} 的语义别名，显式表达 {@code AND (cond1 OR cond2 ...)}。
     */
    public WhereCriterion andAnyOf(UnaryOperator<WhereCriterion> fn) {
        return anyOf(fn);
    }

    /** 追加一个强制括号包裹的 AND 子条件组。 */
    public WhereCriterion andNested(UnaryOperator<WhereCriterion> fn) {
        return append(fn, LinkType.AND, true);
    }

    /** 追加一个强制括号包裹的 OR 子条件组；子组与当前层已有条件之间仍使用当前层的连接符。 */
    public WhereCriterion orNested(UnaryOperator<WhereCriterion> fn) {
        return append(fn, LinkType.OR, true);
    }

    private WhereCriterion append(UnaryOperator<WhereCriterion> fn, LinkType childLink, boolean forceGroup) {
        Objects.requireNonNull(fn, "criterion function must not be null");
        WhereCriterion child = fn.apply(new WhereCriterion(childLink));
        if (child == null || child.isEmpty()) {
            return this;
        }
        return appendChild(child, forceGroup);
    }

    /**
     * 把子条件并入当前层：连接符相同则直接展开，不同则收拢为一个 {@link GroupPredicate}，
     * 由子组自己负责内部连接符与括号，父层不再猜测需要几层括号。
     */
    private WhereCriterion appendChild(WhereCriterion child, boolean forceGroup) {
        if (!forceGroup && this.currentOpt == child.currentOpt) {
            this.predicates.addAll(child.predicates);
        } else {
            this.predicates.add(new GroupPredicate(child.currentOpt, child.predicates, forceGroup));
        }
        return this;
    }

    /**
     * 创建当前条件构建器的独立副本（复制连接符与已收集的谓词列表，后续追加互不影响）。
     */
    public WhereCriterion copy() {
        WhereCriterion copied = new WhereCriterion(this.currentOpt);
        copied.predicates.addAll(this.predicates);
        return copied;
    }

    @Override
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

    private WhereCriterion addBinaryComparison(ColumnMetaProvider provider, String operator, Object value) {
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Binary(operator,
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()),
                    SqlNodes.param(alias.getColumnName(), value, source));
        });
        return this;
    }

    private WhereCriterion addBetween(ColumnMetaProvider provider, Object leftValue, Object rightValue, boolean notBetween) {
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            return new SqlNode.Between(
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()),
                    SqlNodes.param(alias.getColumnName(), leftValue, source),
                    SqlNodes.param(alias.getColumnName(), rightValue, source),
                    notBetween);
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
        predicates.add((ctx, source) -> {
            ColumnAlias alias = provider.resolveAlias(ctx);
            List<SqlNode> nodes = new ArrayList<>(snapshot.size());
            for (Object val : snapshot) {
                nodes.add(SqlNodes.param(alias.getColumnName(), val, source));
            }
            return new SqlNode.In(
                    SqlNodes.column(alias.getTableAlias(), alias.getColumnName()),
                    nodes, notIn);
        });
        return this;
    }

    private WhereCriterion addChunkedInCondition(ColumnMetaProvider provider, Collection<?> values) {
        if (values.size() <= MAX_IN_SIZE) {
            return addInCondition(provider, values, false);
        }
        List<Object> snapshot = new ArrayList<>(values.size());
        for (Object val : values) {
            if (val == null) {
                throw new IllegalArgumentException(
                        "WhereCriterion.inChunked collection must not contain null elements (use isNull explicitly)");
            }
            snapshot.add(val);
        }
        WhereCriterion chunkGroup = new WhereCriterion(LinkType.OR);
        for (int start = 0; start < snapshot.size(); start += MAX_IN_SIZE) {
            int end = Math.min(start + MAX_IN_SIZE, snapshot.size());
            chunkGroup.addInCondition(provider, snapshot.subList(start, end), false);
        }
        return appendChild(chunkGroup, true);
    }

    private static List<Object> toVarargsList(String method, Object first, Object[] rest) {
        if (first == null) {
            throw new IllegalArgumentException(
                    "WhereCriterion." + method + " elements must not be null (use isNull or isNotNull explicitly)");
        }
        if (rest == null) {
            throw new IllegalArgumentException("WhereCriterion." + method + " varargs array must not be null");
        }
        List<Object> list = new ArrayList<>(1 + rest.length);
        list.add(first);
        for (Object item : rest) {
            if (item == null) {
                throw new IllegalArgumentException(
                        "WhereCriterion." + method + " elements must not be null (use isNull or isNotNull explicitly)");
            }
            list.add(item);
        }
        return list;
    }

}
