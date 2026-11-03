package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.support.Ordered;
import com.sondertara.joya.core.kernel.statement.SqlNode;

/**
 * SQL 结构改写扩展点（AST 级）。
 *
 * <p>与 {@link SqlInterceptor} 的分工：
 * <ul>
 *   <li>{@code SqlRewriter} 在<b>渲染前</b>运行，拿到的是一棵 {@link SqlNode} 树，可以安全地加条件、追加列
 *       （物理表名由 {@code NamingPolicy.tableNameResolver} 在渲染期唯一决定，改写器不得篡改表名）；</li>
 *   <li>{@link SqlInterceptor} 在<b>渲染后</b>运行，只拿到 SQL 文本，仅用于 hint/注释等最后一段微调。</li>
 * </ul>
 *
 * <p>实现应为无状态纯函数：返回新节点，不修改输入节点。
 */
@FunctionalInterface
public interface SqlRewriter extends Ordered {

    /**
     * 改写结构树。
     *
     * @param node 当前节点（不可变）
     * @param ctx  本次语句的上下文（操作类型、实体类型、方言、请求属性）
     * @return 改写后的节点；返回原节点表示不修改
     */
    SqlNode rewrite(SqlNode node, SqlRewriteContext ctx);

    /** 是否对该语句生效，默认全部生效。 */
    default boolean supports(SqlRewriteContext ctx) {
        return true;
    }

    @Override
    default int order() {
        return 0;
    }
}
