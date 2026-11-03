package com.sondertara.joya.core.query;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.utils.LambdaUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 查询列/表达式（用于聚合函数与逃生通道，能与 {@link IGetter} 混在 {@code select(...)} 中）。
 *
 * <p>继承 {@link IGetter} 仅作为类型标记；不应在内存中作为函数调用，只由查询构建器通过 {@link #render(RenderContext)} 渲染。
 *
 * @param <R> 表达式结果 Java 类型
 */
public interface Expr<R> extends IGetter<Object, R> {

    /** 表达式结果的 Java 类型（用于按位置从 ResultSet 取值）。 */
    Class<R> resultType();

    /** 把表达式渲染为 SQL 片段（不带 AS 别名）。 */
    String render(RenderContext ctx);

    /** 是否为聚合表达式；用于 count/分页判断，避免扫描渲染后的 SQL 文本。 */
    default boolean isAggregate() {
        return false;
    }

    /** 可选的列别名（用于 {@code list(X.class)} 按名字映射）。 */
    @Nullable
    default String alias() {
        return null;
    }

    /** 可选的所属实体类型（当表达式为单列引用如 {@code dsl.Exprs.col(...)} 时返回对应实体类，其余返回 {@code null}）。 */
    default @Nullable Class<?> entityClass() {
        return null;
    }

    /** 可选的绑定表别名（当表达式由 {@code dsl.Exprs.col(tableAlias, ...)} 创建时返回该表别名）。 */
    default @Nullable String tableAlias() {
        return null;
    }

    /** 可选的结构化列元数据解析（供单列引用保留完整 {@link ColumnAlias}）。 */
    default @Nullable ColumnAlias resolveColumnAlias(RenderContext ctx) {
        return null;
    }

    /** 表达式绑定的参数列表（默认为空）。 */
    default List<SqlParameter> parameters() {
        return List.of();
    }

    /**
     * 为表达式指定列别名（对应 {@code SELECT expr AS alias}）。
     *
     * <p>别名必须是合法纯标识符；非标识符（含分号、引号、空格、点号）一律拒绝，防止 SQL 注入。
     */
    default Expr<R> as(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        String trimmed = alias.trim();
        if (!ColumnMetaProvider.SAFE_ALIAS.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid column alias (potential SQL injection rejected): " + alias);
        }
        Expr<R> self = this;
        return new Expr<>() {
            @Override
            public Class<R> resultType() {
                return self.resultType();
            }

            @Override
            public String render(RenderContext ctx) {
                return self.render(ctx);
            }

            @Override
            public boolean isAggregate() {
                return self.isAggregate();
            }

            @Override
            public String alias() {
                return trimmed;
            }

            @Override
            public @Nullable Class<?> entityClass() {
                return self.entityClass();
            }

            @Override
            public @Nullable String tableAlias() {
                return self.tableAlias();
            }

            @Override
            public @Nullable ColumnAlias resolveColumnAlias(RenderContext ctx) {
                return self.resolveColumnAlias(ctx);
            }

            @Override
            public List<SqlParameter> parameters() {
                return self.parameters();
            }
        };
    }

    /**
     * 用目标 DTO 的属性 getter 作为列别名：{@code Exprs.count().as(OrderVO::getOrderCount)} → {@code AS orderCount}。
     *
     * <p>泛型参数 {@code R} 在编译期即约束目标属性类型与表达式结果类型一致；
     * 仅在 raw type / 通配符擦除场景下由运行期兜底校验。
     */
    default <D> Expr<R> as(IGetter<D, R> targetField) {
        Objects.requireNonNull(targetField, "targetField must not be null");
        Class<?> expected = LambdaUtils.getReturnType(targetField);
        Class<R> actual = resultType();
        if (actual != null && expected != null && !actual.isAssignableFrom(LambdaUtils.boxed(expected))) {
            throw new IllegalArgumentException(
                    "Alias target [" + LambdaUtils.getReceiverClass(targetField).getSimpleName()
                            + "::" + LambdaUtils.getPropertyName(targetField) + "] returns ["
                            + expected.getName() + "] but expression result type is [" + actual.getName() + "]");
        }
        return as(LambdaUtils.getPropertyName(targetField));
    }

    @Override
    default R apply(Object source) {
        throw new UnsupportedOperationException("Expr is a SQL expression marker and cannot be invoked in memory");
    }
}
