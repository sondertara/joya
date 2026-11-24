package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.query.LogicalOperator;
import com.sondertara.joya.core.query.SqlPredicate;
import com.sondertara.joya.core.query.TableScope;

import java.util.List;

/**
 * 条件树中的组合节点：把一组子谓词按指定逻辑连接符组装为 {@link SqlNode.Binary}，并按需包裹 {@link SqlNode.Group}。
 */
final class GroupPredicate implements SqlPredicate {

    private final LogicalOperator operator;
    private final List<SqlPredicate> children;
    private final boolean alwaysWrap;

    GroupPredicate(LogicalOperator operator, List<SqlPredicate> children, boolean alwaysWrap) {
        this.operator = operator;
        this.children = List.copyOf(children);
        this.alwaysWrap = alwaysWrap;
    }

    @Override
    public SqlNode toNode(TableScope scope, ParameterSource source) {
        SqlNode.BinaryOp binaryOp = operator == LogicalOperator.OR ? SqlNode.BinaryOp.OR : SqlNode.BinaryOp.AND;
        SqlNode result = null;
        for (SqlPredicate child : children) {
            SqlNode node = child.toNode(scope, source);
            if (node == null) {
                continue;
            }
            result = result == null ? node : new SqlNode.Binary(binaryOp, result, node);
        }
        if (result == null) {
            return null;
        }
        return alwaysWrap ? new SqlNode.Group(result) : result;
    }
}
