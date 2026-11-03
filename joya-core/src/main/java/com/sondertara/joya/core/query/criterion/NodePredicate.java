package com.sondertara.joya.core.query.criterion;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.SqlPredicate;

/**
 * 结构化谓词：{@code toNode} 是真相源，{@code render} 由 AST 渲染器统一完成。
 */
final class NodePredicate implements SqlPredicate {

    interface NodeFactory {
        SqlNode create(RenderContext ctx, ParameterSource source);
    }

    private final NodeFactory factory;

    NodePredicate(NodeFactory factory) {
        this.factory = factory;
    }

    @Override
    public SqlNode toNode(RenderContext ctx, ParameterSource source) {
        return factory.create(ctx, source);
    }

    @Override
    public void render(RenderContext ctx, StringBuilder sql, SqlParameterList params, ParameterSource source) {
        SqlNode node = toNode(ctx, source);
        if (node == null) {
            return;
        }
        var statement = SqlRenderer.render(node, StatementMetadata.empty());
        sql.append(statement.sql());
        for (var parameter : statement.parameters()) {
            params.add(parameter);
        }
    }
}
