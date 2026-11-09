package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.LockMode;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.RenderContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询渲染器：QuerySpec → SqlNode.Select → SqlStatement。不执行 SQL。
 */
final class QueryRenderer<T> {

    private static final List<SqlNode> COUNT_STAR_PROJECTION = List.of(SqlNodes.raw("COUNT(*)"));
    private static final List<SqlNode> SUBQUERY_COUNT_PROJECTION = List.of(SqlNodes.raw("1 AS c"));

    private final ExecutionKernel kernel;

    QueryRenderer(ExecutionKernel kernel) {
        this.kernel = kernel;
    }

    RenderContext renderContext(QuerySpec<T> spec) {
        return RenderContext.forQuery(
                spec.rootType(),
                spec.joinCriterion().getJoinSegments(),
                kernel != null ? kernel.namingPolicy() : NamingPolicy.DEFAULT);
    }

    SqlStatement renderStatement(QuerySpec<T> spec, List<SqlNode> projections,
                                 boolean includeOrderBy, RenderContext ctx) {
        return renderStatement(spec, projections, includeOrderBy, spec.lockMode(), ctx);
    }

    private SqlStatement renderStatement(QuerySpec<T> spec, List<SqlNode> projections,
                                         boolean includeOrderBy, LockMode lockMode, RenderContext ctx) {
        SqlNode.From fromNode = ctx.toFromNode();
        SqlNode whereNode = spec.whereCriterion().toNode(ctx, ParameterSource.WHERE);
        List<SqlNode> groupByNodes = groupByNodes(spec, ctx);
        SqlNode havingNode = spec.havingCriterion().toNode(ctx, ParameterSource.WHERE);
        List<SqlNode> orderByNodes = (!includeOrderBy || spec.orderSpecs().isEmpty())
                ? List.of()
                : orderByNodes(spec, ctx);

        var peekedDialect = kernel != null ? kernel.peekDialect() : null;
        StatementMetadata meta = StatementMetadata.builder(Operation.SELECT)
                .tables(ctx.extractTables())
                .entityType(spec.rootType())
                .dbType(peekedDialect != null ? peekedDialect.dbType() : null)
                .build();

        SqlNode.Select select = new SqlNode.Select(
                spec.distinct(),
                projections,
                fromNode,
                whereNode,
                groupByNodes,
                havingNode,
                orderByNodes,
                lockMode);
        return SqlRenderer.render(select, meta);
    }

    SqlStatement renderCountStatement(QuerySpec<T> spec, List<SqlNode> projectionNodes,
                                      boolean hasAggregate, RenderContext ctx) {
        if (!spec.distinct() && !hasAggregate && spec.groupByColumns().isEmpty()
                && spec.havingCriterion().isEmpty()) {
            return renderStatement(spec, COUNT_STAR_PROJECTION, false, LockMode.NONE, ctx);
        }
        SqlStatement inner = renderStatement(spec,
                (spec.distinct() || hasAggregate) ? projectionNodes : SUBQUERY_COUNT_PROJECTION,
                false, LockMode.NONE, ctx);
        if (inner.ast() instanceof SqlNode.Select select) {
            return SqlRenderer.render(new SqlNode.Count(select), inner.metadata());
        }
        throw new IllegalStateException("QueryRenderer must produce a SqlNode.Select AST");
    }

    private List<SqlNode> groupByNodes(QuerySpec<T> spec, RenderContext ctx) {
        List<SqlNode> groups = new ArrayList<>(spec.groupByColumns().size());
        for (ColumnMetaProvider col : spec.groupByColumns()) {
            groups.add(col.resolveNode(ctx));
        }
        return groups;
    }

    private List<SqlNode> orderByNodes(QuerySpec<T> spec, RenderContext ctx) {
        List<SqlNode> orders = new ArrayList<>(spec.orderSpecs().size());
        for (QuerySpec.OrderSpec order : spec.orderSpecs()) {
            orders.add(new SqlNode.Order(order.column().resolveNode(ctx), order.direction().name()));
        }
        return orders;
    }
}
