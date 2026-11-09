package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.support.Ordered;
import com.sondertara.joya.core.kernel.statement.SqlNode;

/**
 * SQL 结构改写扩展点（AST 级），也是 Joya 唯一的 SQL 改写入口。
 *
 * <p>在<b>渲染前</b>运行，拿到的是一棵 {@link SqlNode} 树，可以安全地加条件、追加列
 * （物理表名由 {@code NamingPolicy.tableNameResolver} 在渲染期唯一决定，改写器不得篡改表名）。
 * 观测/日志请使用 {@code SqlExecutionListener}；不要在这里拼接 SQL 文本。
 *
 * <p>实现应为无状态纯函数：返回新节点，不修改输入节点；不得改 SELECT 投影或语句类型。
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
