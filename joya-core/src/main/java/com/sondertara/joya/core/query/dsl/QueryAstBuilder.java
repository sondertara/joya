package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.AstStatement;
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
import com.sondertara.joya.core.query.TableScope;
import com.sondertara.joya.core.query.pagination.SortDirection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询 AST 构建器（替代原 {@code QueryRenderer}）：将 {@link QuerySpec} 编译为结构化 {@link AstStatement}。
 *
 * <p>在线执行链路只构建 {@link AstStatement} 直接递交 {@link ExecutionKernel}，<b>零预渲染开销</b>；
 * 仅当用户显式调用离线检视 {@code toSqlStatement()} 时才调用 {@link SqlRenderer} 生成物理语句。
 */
final class QueryAstBuilder<T> {

    private static final List<SqlNode> COUNT_STAR_PROJECTION = List.of(SqlNodes.countStar());
    private static final List<SqlNode> SUBQUERY_COUNT_PROJECTION = List.of(SqlNodes.alias(SqlNodes.raw("1"), "c"));

    private final @Nullable ExecutionKernel kernel;

    QueryAstBuilder(@Nullable ExecutionKernel kernel) {
        this.kernel = kernel;
    }

    NamingPolicy namingPolicy() {
        return kernel != null ? kernel.namingPolicy() : NamingPolicy.DEFAULT;
    }

    TableScope tableScope(QuerySpec<T> spec) {
        return TableScope.forQuery(spec.rootType(), spec.joinCriterion().getJoinSegments(), namingPolicy());
    }

    AstStatement buildSelectAst(QuerySpec<T> spec, List<SqlNode> projections,
                                boolean includeOrderBy, TableScope scope) {
        return buildSelectAst(spec, projections, includeOrderBy, spec.lockMode(), scope);
    }

    private AstStatement buildSelectAst(QuerySpec<T> spec, List<SqlNode> projections,
                                        boolean includeOrderBy, LockMode lockMode, TableScope scope) {
        SqlNode.From fromNode = scope.toFromNode();
        SqlNode whereNode = spec.whereCriterion().toNode(scope, ParameterSource.WHERE);
        List<SqlNode> groupByNodes = groupByNodes(spec, scope);
        SqlNode havingNode = spec.havingCriterion().toNode(scope, ParameterSource.HAVING);
        List<SqlNode> orderByNodes = (!includeOrderBy || spec.orderSpecs().isEmpty())
                ? List.of()
                : orderByNodes(spec, scope);

        var peekedDialect = kernel != null ? kernel.peekDialect() : null;
        StatementMetadata meta = StatementMetadata.builder(Operation.SELECT)
                .tables(scope.extractTables())
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
        return new AstStatement(select, meta);
    }

    AstStatement buildCountAst(QuerySpec<T> spec, List<SqlNode> projectionNodes,
                               boolean hasAggregate, TableScope scope) {
        if (!spec.distinct() && !hasAggregate && spec.groupByColumns().isEmpty()
                && spec.havingCriterion().isEmpty()) {
            return buildSelectAst(spec, COUNT_STAR_PROJECTION, false, LockMode.NONE, scope);
        }
        AstStatement inner = buildSelectAst(spec,
                (spec.distinct() || hasAggregate) ? projectionNodes : SUBQUERY_COUNT_PROJECTION,
                false, LockMode.NONE, scope);
        if (inner.ast() instanceof SqlNode.Select select) {
            return new AstStatement(new SqlNode.Count(select), inner.metadata());
        }
        throw new IllegalStateException("QueryAstBuilder must produce a SqlNode.Select AST");
    }

    SqlStatement renderOffline(AstStatement astStatement) {
        return SqlRenderer.render(astStatement, namingPolicy());
    }

    private List<SqlNode> groupByNodes(QuerySpec<T> spec, TableScope scope) {
        if (spec.groupByColumns().isEmpty()) {
            return List.of();
        }
        List<SqlNode> groups = new ArrayList<>(spec.groupByColumns().size());
        for (ColumnMetaProvider col : spec.groupByColumns()) {
            groups.add(col.resolveNode(scope));
        }
        return groups;
    }

    private List<SqlNode> orderByNodes(QuerySpec<T> spec, TableScope scope) {
        List<SqlNode> orders = new ArrayList<>(spec.orderSpecs().size());
        for (QuerySpec.OrderSpec order : spec.orderSpecs()) {
            SqlNode.SortDir dir = order.direction() == SortDirection.DESC
                    ? SqlNode.SortDir.DESC
                    : SqlNode.SortDir.ASC;
            orders.add(new SqlNode.Order(order.column().resolveNode(scope), dir));
        }
        return orders;
    }
}
