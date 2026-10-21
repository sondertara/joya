package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.SqlStatement;

/**
 * 拦截器链推进接口，对标 Servlet FilterChain / MyBatis Interceptor 的 {@code plugin} 调用。
 */
public interface Chain {
    SqlStatement proceed(RewriteContext ctx);
}
