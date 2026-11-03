package com.sondertara.joya.core.query;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 延迟求值的列元数据提供器。
 *
 * <p>核心职责：
 * <ul>
 *   <li>在 DSL 调用期（{@code where / join / groupBy / having / orderBy / select}）仅提取并持有列的惰性标识（如实体类型 + 属性名），
 *       不触发 {@code t0 / t1} 表别名分配与字符串拼接；</li>
 *   <li>对无捕获的 {@link IGetter} 方法引用按合成类 Class 级缓存解析结果，彻底消除每次查询重复反射调用 {@code writeReplace} 的开销；</li>
 *   <li>在运行期（{@code toSqlStatement()}）接收 {@link RenderContext} 统一翻译为带别名的列（多表查询）或无别名的列（单表改删查）。</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public sealed interface ColumnMetaProvider
        permits ColumnMetaProvider.GetterColumnMetaProvider,
                ColumnMetaProvider.NameColumnMetaProvider,
                ColumnMetaProvider.ExprColumnMetaProvider {

    /** 合法的列或字段标识符正则（可选一段前缀：alias.col 或 Table.field）。 */
    Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /** 合法的表别名或列别名正则（纯标识符，不含点号、空格、引号或分号）。 */
    Pattern SAFE_ALIAS = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 无捕获方法引用合成类到 GetterColumnMetaProvider 的 ClassValue 缓存（不持有 Lambda 实例，避免锁争用与泄漏）。 */
    final class ProviderCache {
        static final ClassValue<ProviderCache> CACHE = new ClassValue<>() {
            @Override
            protected ProviderCache computeValue(Class<?> type) {
                return new ProviderCache(type.getDeclaredFields().length == 0);
            }
        };
        final boolean stateless;
        volatile @Nullable ColumnMetaProvider provider;

        ProviderCache(boolean stateless) {
            this.stateless = stateless;
        }
    }

    /**
     * 所属实体类型（若为字符串列名或聚合表达式则为 {@code null}）。
     */
    @Nullable
    Class<?> entityClass();

    /**
     * 显式绑定的表别名（如由 {@code Exprs.col("mgr", Emp::getName)} 指定时返回 {@code "mgr"}，其余返回 {@code null}）。
     */
    default @Nullable String tableAlias() {
        return null;
    }

    /**
     * 实体属性名或表达式别名。
     */
    @Nullable
    String propertyName();

    /**
     * 用于去重与比较的规范化唯一键（无需分配表别名即可在 API 期比较相等性）。
     */
    String canonicalKey();

    /**
     * 在给定渲染上下文中解析为完整的 {@link ColumnAlias}。
     */
    ColumnAlias resolveAlias(RenderContext ctx);

    /**
     * 在给定渲染上下文中解析为 SQL 列引用片段（如 {@code t0.user_name} 或单表模式下的 {@code user_name}）。
     */
    default String resolveSql(RenderContext ctx) {
        return resolveAlias(ctx).identity();
    }

    /** 解析为结构化列节点；表达式提供器可覆盖为 Raw/Function。 */
    default SqlNode resolveNode(RenderContext ctx) {
        ColumnAlias alias = resolveAlias(ctx);
        return SqlNodes.column(alias.getTableAlias(), alias.getColumnName());
    }

    /**
     * 从 {@link IGetter}（方法引用或 {@link Expr}）构造列元数据提供器。
     */
    static ColumnMetaProvider fromGetter(IGetter<?, ?> getter) {
        Objects.requireNonNull(getter, "column getter must not be null");
        if (getter instanceof Expr<?> expr) {
            return new ExprColumnMetaProvider(expr);
        }
        Class<?> getterClass = getter.getClass();
        ProviderCache holder = ProviderCache.CACHE.get(getterClass);
        if (holder.stateless) {
            ColumnMetaProvider cached = holder.provider;
            if (cached != null) {
                return cached;
            }
            ColumnMetaProvider parsed = parseGetter(getter);
            if (parsed instanceof GetterColumnMetaProvider) {
                holder.provider = parsed;
            }
            return parsed;
        }
        return parseGetter(getter);
    }

    /**
     * 从外部传入的字段名或列名构造列元数据提供器（构造期即拦截非法 SQL 注入标识符）。
     */
    static ColumnMetaProvider fromName(String fieldOrColumn) {
        if (Strings.isBlank(fieldOrColumn) || !SAFE_IDENTIFIER.matcher(fieldOrColumn.trim()).matches()) {
            throw new IllegalArgumentException(
                    "Invalid column or field identifier (potential SQL injection rejected): " + fieldOrColumn);
        }
        return new NameColumnMetaProvider(fieldOrColumn.trim());
    }

    private static ColumnMetaProvider parseGetter(IGetter<?, ?> getter) {
        String methodName = LambdaUtils.getImplMethodName(getter);
        if (methodName.startsWith("lambda$")) {
            throw new IllegalArgumentException(
                    "Synthetic lambda is not supported as a column reference; use an entity getter method reference "
                            + "(e.g. User::getName) or Exprs.* instead");
        }
        Class<?> entityClass = LambdaUtils.getReceiverClass(getter);
        String propertyName = LambdaUtils.getPropertyName(getter);
        return new GetterColumnMetaProvider(entityClass, propertyName);
    }

    /**
     * 标准实体方法引用（如 {@code User::getName}）的列元数据提供器。
     */
    record GetterColumnMetaProvider(Class<?> entityClass, String propertyName) implements ColumnMetaProvider {

        public GetterColumnMetaProvider {
            Objects.requireNonNull(entityClass, "entityClass must not be null");
            Objects.requireNonNull(propertyName, "propertyName must not be null");
        }

        @Override
        public String canonicalKey() {
            return entityClass.getName() + "#" + propertyName;
        }

        @Override
        public ColumnAlias resolveAlias(RenderContext ctx) {
            return ctx.resolveGetterColumn(entityClass, propertyName);
        }
    }

    /**
     * 字段名或列名字符串的列元数据提供器。
     */
    record NameColumnMetaProvider(String identifier) implements ColumnMetaProvider {

        public NameColumnMetaProvider {
            Objects.requireNonNull(identifier, "identifier must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return null;
        }

        @Override
        public String propertyName() {
            int dot = identifier.indexOf('.');
            return dot >= 0 ? identifier.substring(dot + 1) : identifier;
        }

        @Override
        public String canonicalKey() {
            return "name:" + identifier;
        }

        @Override
        public ColumnAlias resolveAlias(RenderContext ctx) {
            return ctx.resolveIdentifierColumn(identifier);
        }
    }

    /**
     * SQL 表达式（聚合函数 / {@code Exprs.raw}）的元数据提供器。
     */
    record ExprColumnMetaProvider(Expr<?> expr) implements ColumnMetaProvider {

        public ExprColumnMetaProvider {
            Objects.requireNonNull(expr, "expr must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return expr.entityClass();
        }

        @Override
        public @Nullable String tableAlias() {
            return expr.tableAlias();
        }

        @Override
        public @Nullable String propertyName() {
            return expr.alias();
        }

        @Override
        public String canonicalKey() {
            if (expr.entityClass() != null && expr.alias() != null) {
                String prefix = expr.tableAlias() != null ? expr.tableAlias() + "@" : "";
                return prefix + expr.entityClass().getName() + "#" + expr.alias();
            }
            return "expr:" + System.identityHashCode(expr);
        }

        @Override
        public ColumnAlias resolveAlias(RenderContext ctx) {
            ColumnAlias colAlias = expr.resolveColumnAlias(ctx);
            if (colAlias != null) {
                return expr.alias() != null ? colAlias.withColumnAlias(expr.alias()) : colAlias;
            }
            return new ColumnAlias(null, expr.render(ctx), null, expr.alias());
        }

        @Override
        public String resolveSql(RenderContext ctx) {
            return expr.render(ctx);
        }

        @Override
        public SqlNode resolveNode(RenderContext ctx) {
            ColumnAlias colAlias = expr.resolveColumnAlias(ctx);
            if (colAlias != null) {
                return SqlNodes.column(colAlias.getTableAlias(), colAlias.getColumnName());
            }
            return SqlNodes.raw(expr.render(ctx), expr.parameters());
        }
    }
}
