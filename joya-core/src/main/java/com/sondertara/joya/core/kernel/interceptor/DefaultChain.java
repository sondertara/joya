package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.List;

/**
 * 默认拦截器链实现（责任链模式）。
 * <p>
 * 按 {@link Ordered#order()} 升序、同值按 {@code Class.getName()} 字典序确定性执行。
 * 跳过 {@link SqlInterceptor#supports(RewriteContext)} 返回 {@code false} 的拦截器。
 */
public final class DefaultChain implements Chain {

    private final List<SqlInterceptor> interceptors;
    private int index = 0;

    public DefaultChain(List<SqlInterceptor> interceptors) {
        this.interceptors = interceptors;
    }

    @Override
    public SqlStatement proceed(RewriteContext ctx) {
        while (index < interceptors.size()) {
            SqlInterceptor interceptor = interceptors.get(index++);
            if (interceptor.supports(ctx)) {
                return interceptor.intercept(ctx, this);
            }
        }
        return ctx.statement();
    }
}
