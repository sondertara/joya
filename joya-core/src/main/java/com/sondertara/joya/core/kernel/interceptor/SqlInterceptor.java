package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.support.Ordered;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

/**
 * SQL 文本改写拦截器（兼容层）。
 *
 * <p>它拿到的是已经渲染完成的 {@link SqlStatement}，只适合 hint、注释、字面量等最后一段文本微调。
 * <b>结构变更请使用 {@link SqlRewriter}</b>：租户条件、软删、审计列都需要在 AST 层完成
 * （分表与 schema 切换由 {@code NamingPolicy.tableNameResolver} 在渲染期完成）；
 * 文本层无法安全地追加参数，批量模式下尤其如此。
 *
 * <ul>
 *   <li>纯函数：只改 {@link SqlStatement} 并返回新实例，不碰渲染逻辑、不持有连接。</li>
 *   <li>无反射魔法：无 {@code @Intercepts}、无 {@code Invocation}，直接实现接口。</li>
 *   <li>按 {@link #order()} 排序执行；同值按类名字典序保证确定性。</li>
 * </ul>
 *
 * <p><b>批量边界</b>：{@code BatchSqlStatement} 同样经过本链，拦截器收到的是整批共用的 SQL 模板探针
 * （参数为空，整批只触发一次，{@link RewriteContext#isBatch()} 为 {@code true}）。
 * 批量模式下只允许改写文本；追加参数或改变占位符个数会抛 {@link UnsupportedOperationException}，
 * 结构变更请使用 {@link SqlRewriter}。
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
