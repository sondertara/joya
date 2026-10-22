package com.sondertara.joya.core.query;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.dsl.Expr;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
                ColumnMetaProvider.ExprColumnMetaProvider,
                ColumnMetaProvider.DynamicLambdaColumnMetaProvider {

    /** 合法的列或字段标识符正则（可选一段前缀：alias.col 或 Table.field）。 */
    Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /** 无捕获 Getter 合成类到 ColumnMetaProvider 的全局缓存。 */
    Map<Class<?>, ColumnMetaProvider> GETTER_PROVIDER_CACHE = new ConcurrentHashMap<>();

    /**
     * 所属实体类型（若为字符串列名或聚合表达式则为 {@code null}）。
     */
    @Nullable
    Class<?> entityClass();

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

    /**
     * 从 {@link IGetter}（方法引用或 {@link Expr}）构造列元数据提供器。
     */
    static ColumnMetaProvider fromGetter(IGetter<?, ?> getter) {
        Objects.requireNonNull(getter, "column getter must not be null");
        if (getter instanceof Expr<?> expr) {
            return new ExprColumnMetaProvider(expr);
        }
        Class<?> getterClass = getter.getClass();
        if (getterClass.getDeclaredFields().length == 0) {
            return GETTER_PROVIDER_CACHE.computeIfAbsent(getterClass, k -> parseGetter(getter));
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
            return new DynamicLambdaColumnMetaProvider(getter);
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
            return null;
        }

        @Override
        public @Nullable String propertyName() {
            return expr.alias();
        }

        @Override
        public String canonicalKey() {
            return "expr:" + System.identityHashCode(expr);
        }

        @Override
        public ColumnAlias resolveAlias(RenderContext ctx) {
            String rendered = expr.render(ctx);
            ColumnAlias alias = new ColumnAlias();
            alias.setColumnName(rendered);
            alias.setColumnAlias(expr.alias());
            return alias;
        }

        @Override
        public String resolveSql(RenderContext ctx) {
            return expr.render(ctx);
        }
    }

    /**
     * 兼容手写合成 Lambda（如 {@code u -> "SUM(t0.age)"}）的提供器。
     */
    record DynamicLambdaColumnMetaProvider(IGetter<?, ?> fn) implements ColumnMetaProvider {

        public DynamicLambdaColumnMetaProvider {
            Objects.requireNonNull(fn, "fn must not be null");
        }

        @Override
        public @Nullable Class<?> entityClass() {
            return null;
        }

        @Override
        public @Nullable String propertyName() {
            Object val = fn.apply(null);
            ColumnPair pair = RawColumnParser.parse(Objects.toString(val, ""));
            return pair != null ? pair.getValue() : null;
        }

        @Override
        public String canonicalKey() {
            return "lambda:" + Objects.toString(fn.apply(null), "");
        }

        @Override
        public ColumnAlias resolveAlias(RenderContext ctx) {
            Object val = fn.apply(null);
            ColumnPair pair = RawColumnParser.parse(Objects.toString(val, ""), ctx.namingPolicy().columnUppercase());
            ColumnAlias alias = new ColumnAlias();
            if (pair != null) {
                alias.setColumnName(pair.getKey());
                alias.setColumnAlias(pair.getValue());
            }
            return alias;
        }
    }
}
