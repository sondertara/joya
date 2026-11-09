package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.SqlPredicate;

import java.util.List;

/**
 * 条件树中的组合节点：把一组谓词按指定连接符渲染，并在作为子条件时按需加括号。
 *
 * <p>这是 {@link WhereCriterion} 从“平铺谓词列表 + 单一分隔符”走向“树”的最小实现：
 * 每个节点只负责自己这一层的连接符，嵌套层级由 {@code GroupPredicate} 递归渲染，
 * 因此括号优先级不依赖父层的 {@code wrap} 判断。
 */
final class GroupPredicate implements SqlPredicate {

    private final LinkType linkType;
    private final List<SqlPredicate> children;
    private final boolean alwaysWrap;

    GroupPredicate(LinkType linkType, List<SqlPredicate> children, boolean alwaysWrap) {
        this.linkType = linkType;
        this.children = List.copyOf(children);
        this.alwaysWrap = alwaysWrap;
    }

    @Override
    public SqlNode toNode(RenderContext ctx, ParameterSource source) {
        SqlNode result = null;
        for (SqlPredicate child : children) {
            SqlNode node = child.toNode(ctx, source);
            if (node == null) {
                continue;
            }
            result = result == null ? node : new SqlNode.Binary(linkType.name(), result, node);
        }
        if (result == null) {
            return null;
        }
        return alwaysWrap ? new SqlNode.Group(result) : result;
    }


}
