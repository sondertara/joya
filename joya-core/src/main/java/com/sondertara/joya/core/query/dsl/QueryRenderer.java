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
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.naming.NamingPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 查询渲染器：QuerySpec → SqlNode.Select → SqlStatement。不执行 SQL。
 */
final class QueryRenderer<T> {

    private final ExecutionKernel template;

    QueryRenderer(ExecutionKernel template) {
        this.template = template;
    }

    RenderContext renderContext(QuerySpec<T> spec) {
        return RenderContext.forQuery(
                spec.rootType(),
                spec.joinCriterion().getJoinSegments(),
                template != null ? template.namingPolicy() : NamingPolicy.DEFAULT);
    }

    SqlStatement renderStatement(QuerySpec<T> spec, List<String> selectColumns) {
        return renderStatementNodes(spec, rawNodes(selectColumns), true, renderContext(spec));
    }

    SqlStatement renderStatement(QuerySpec<T> spec, List<String> selectColumns,
                                 boolean includeOrderBy, RenderContext ctx) {
        return renderStatementNodes(spec, rawNodes(selectColumns), includeOrderBy, ctx);
    }

    SqlStatement renderStatementNodes(QuerySpec<T> spec, List<SqlNode> projections) {
        return renderStatementNodes(spec, projections, true, renderContext(spec));
    }

    SqlStatement renderStatementNodes(QuerySpec<T> spec, List<SqlNode> projections,
                                      boolean includeOrderBy, RenderContext ctx) {
        return renderStatementNodes(spec, projections, includeOrderBy, spec.lockMode(), ctx);
    }

    private SqlStatement renderStatementNodes(QuerySpec<T> spec, List<SqlNode> projections,
                                              boolean includeOrderBy, LockMode lockMode, RenderContext ctx) {
        SqlNode.From fromNode = ctx.toFromNode();
        SqlNode whereNode = spec.whereCriterion().toNode(ctx, ParameterSource.WHERE);
        List<SqlNode> groupByNodes = groupByNodes(spec, ctx);
        SqlNode havingNode = spec.havingCriterion().toNode(ctx, ParameterSource.WHERE);
        List<SqlNode> orderByNodes = (!includeOrderBy || spec.orderSpecs().isEmpty())
                ? List.of()
                : orderByNodes(spec, ctx);

        StatementMetadata meta = StatementMetadata.builder(Operation.SELECT)
                .tables(ctx.extractTables())
                .entityType(spec.rootType())
                .dbType(template != null && template.peekDialect() != null ? template.peekDialect().dbType() : null)
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
            return renderStatementNodes(spec, rawNodes(List.of("COUNT(*)")), false, LockMode.NONE, ctx);
        }
        SqlStatement inner = renderStatementNodes(spec,
                (spec.distinct() || hasAggregate) ? projectionNodes : rawNodes(List.of("1 AS c")),
                false, LockMode.NONE, ctx);
        if (inner.ast() instanceof SqlNode.Select select) {
            return SqlRenderer.render(new SqlNode.Count(select), inner.metadata());
        }
        throw new IllegalStateException("QueryRenderer must produce a SqlNode.Select AST");
    }

    private static List<SqlNode> rawNodes(List<String> columns) {
        List<SqlNode> nodes = new ArrayList<>(columns.size());
        for (String column : columns) {
            nodes.add(SqlNodes.raw(column));
        }
        return nodes;
    }

    private List<SqlNode> groupByNodes(QuerySpec<T> spec, RenderContext ctx) {
        List<SqlNode> groups = new ArrayList<>(spec.groupByColumns().size());
        for (ColumnMetaProvider col : spec.groupByColumns()) {
            groups.add(columnNode(col, ctx));
        }
        return groups;
    }

    private List<SqlNode> orderByNodes(QuerySpec<T> spec, RenderContext ctx) {
        List<SqlNode> orders = new ArrayList<>(spec.orderSpecs().size());
        for (QuerySpec.OrderSpec order : spec.orderSpecs()) {
            orders.add(new SqlNode.Order(columnNode(order.column(), ctx), order.direction().name()));
        }
        return orders;
    }

    private static SqlNode columnNode(ColumnMetaProvider column, RenderContext ctx) {
        var alias = column.resolveAlias(ctx);
        return SqlNodes.column(alias.getTableAlias(), alias.getColumnName());
    }

}
