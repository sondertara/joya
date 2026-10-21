package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.SqlStatement;

/**
 * SQL 改写拦截器 —— 全框架统一的 SQL 拦截扩展点。
 * <p>
 * 设计原则（对标 MyBatis Interceptor / EF Core DbCommandInterceptor，但更轻）：
 * <ul>
 *   <li>纯函数：只改 {@link SqlStatement} 并返回新实例，不碰渲染逻辑、不持有连接。</li>
 *   <li>无反射魔法：无 {@code @Intercepts}、无 {@code Invocation}，直接实现接口。</li>
 *   <li>正交分层：分库分表/租户/软删/审计均为独立拦截器，按 {@link #order()} 排序执行。</li>
 * </ul>
 */
public interface SqlInterceptor extends Ordered {

    /**
     * 拦截并改写 SQL。
     *
     * @param ctx   改写上下文，通过 {@code ctx.rewrite(...)} 推进结果
     * @param chain 后续拦截器链，调用 {@code chain.proceed(ctx)} 继续
     * @return 最终语句（通常由 {@code chain.proceed(ctx)} 返回）
     */
    SqlStatement intercept(RewriteContext ctx, Chain chain);

    /** 排序权重，升序执行。默认 0。 */
    @Override
    default int order() {
        return 0;
    }

    /** 是否对该语句生效，用于按操作类型/表名过滤，避免拦截器内散落 {@code if}。 */
    default boolean supports(RewriteContext ctx) {
        return true;
    }
}
