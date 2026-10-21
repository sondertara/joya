package com.sondertara.joya.core.query.dsl;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.query.RenderContext;
import org.jspecify.annotations.Nullable;

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

    /** 可选的列别名（用于 {@code list(X.class)} 按名字映射）。 */
    @Nullable
    default String alias() {
        return null;
    }

    /** 为表达式指定别名（对应 {@code SELECT expr AS alias}）。 */
    default Expr<R> as(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
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
            public String alias() {
                return alias;
            }
        };
    }

    @Override
    default R apply(Object source) {
        throw new UnsupportedOperationException("Expr is a SQL expression marker and cannot be invoked in memory");
    }
}
