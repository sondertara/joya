package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 SQL 文本渲染器 —— 全仓唯一负责把 {@link SqlNode} 结构树渲染为标准 DQL / DML SQL 字符串的组件。
 *
 * <p>各 {@code *StatementFactory} 与查询 DSL 只负责构建结构化 {@link SqlNode}，
 * 最终 SQL 文本与有序参数统一交由 {@link #render(SqlNode, StatementMetadata)} 单点生成，
 * 消除单条、批处理、查询流之间的双轨拼接。
 *
 * <p><b>能力边界</b>：方言层的分页 / 计数包装（{@code kernel.dialect}）属于数据库特有语法，
 * 仍由各方言自行拼接，不在本类职责内。
 */
public final class SqlRenderer {

    private SqlRenderer() {
    }

    /**
     * 把 AST 渲染为不可变 {@link SqlStatement}；参数按遍历顺序统一重排。
     */
    public static SqlStatement render(SqlNode node, StatementMetadata metadata) {
        SqlParameterList params = new SqlParameterList();
        StringBuilder sql = new StringBuilder();
        DbType dbType = metadata != null ? metadata.dbType() : null;
        renderNode(node, sql, params, dbType);
        return SqlStatement.of(sql.toString(), params.toList(), metadata, node);
    }

    private static void renderNode(SqlNode node, StringBuilder sql, SqlParameterList params, @Nullable DbType dbType) {
        if (node instanceof SqlNode.Raw raw) {
            sql.append(raw.sql());
            for (SqlParameter parameter : raw.parameters()) {
                params.add(parameter);
            }
            return;
        }
        if (node instanceof SqlNode.Table table) {
            sql.append(table.physicalName());
            if (!table.alias().isBlank()) {
                sql.append(' ').append(table.alias());
            }
            return;
        }
        if (node instanceof SqlNode.Column column) {
            if (column.table() != null && !column.table().alias().isBlank()) {
                sql.append(column.table().alias()).append('.');
            }
            sql.append(column.name());
            return;
        }
        if (node instanceof SqlNode.Param param) {
            sql.append('?');
            params.add(param.name(), param.value(), param.source());
            return;
        }
        if (node instanceof SqlNode.Binary binary) {
            renderOperand(binary.left(), binary.op(), sql, params, dbType);
            if (binary.left() != null) {
                sql.append(' ').append(binary.op()).append(' ');
            }
            renderOperand(binary.right(), binary.op(), sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Between between) {
            renderNode(between.value(), sql, params, dbType);
            sql.append(between.negated() ? " NOT BETWEEN " : " BETWEEN ");
            renderNode(between.lower(), sql, params, dbType);
            sql.append(" AND ");
            renderNode(between.upper(), sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.In in) {
            renderNode(in.value(), sql, params, dbType);
            sql.append(in.negated() ? " NOT IN (" : " IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                renderNode(in.values().get(i), sql, params, dbType);
            }
            sql.append(')');
            return;
        }
        if (node instanceof SqlNode.Unary unary) {
            renderNode(unary.operand(), sql, params, dbType);
            sql.append(' ').append(unary.op());
            return;
        }
        if (node instanceof SqlNode.Like like) {
            renderNode(like.value(), sql, params, dbType);
            sql.append(like.negated() ? " NOT LIKE " : " LIKE ");
            renderNode(like.pattern(), sql, params, dbType);
            if (like.escaped()) {
                sql.append(dbType != null && dbType.isLikeMysql() ? " ESCAPE '\\\\'" : " ESCAPE '\\'");
            }
            return;
        }
        if (node instanceof SqlNode.Order order) {
            renderNode(order.expression(), sql, params, dbType);
            sql.append(' ').append(order.direction());
            return;
        }
        if (node instanceof SqlNode.Alias alias) {
            renderNode(alias.expression(), sql, params, dbType);
            if (!alias.alias().isBlank()) {
                sql.append(" AS ").append(alias.alias());
            }
            return;
        }
        if (node instanceof SqlNode.Group group) {
            sql.append('(');
            renderNode(group.body(), sql, params, dbType);
            sql.append(')');
            return;
        }
        if (node instanceof SqlNode.Assignment assignment) {
            sql.append(assignment.column()).append(" = ");
            renderNode(assignment.value(), sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.From from) {
            renderNode(from.root(), sql, params, dbType);
            for (SqlNode.Join join : from.joins()) {
                sql.append(' ').append(join.type()).append(' ');
                renderNode(join.table(), sql, params, dbType);
                sql.append(" ON ");
                renderNode(join.on(), sql, params, dbType);
            }
            return;
        }
        if (node instanceof SqlNode.Join join) {
            sql.append(join.type()).append(' ');
            renderNode(join.table(), sql, params, dbType);
            sql.append(" ON ");
            renderNode(join.on(), sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Count count) {
            StringBuilder inner = new StringBuilder();
            renderNode(count.select().withLockMode(LockMode.NONE), inner, params, dbType);
            sql.append("SELECT COUNT(*) FROM (").append(inner).append(") TEMP_T");
            return;
        }
        if (node instanceof SqlNode.Select select) {
            renderSelectNode(select, sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Insert insert) {
            renderInsertNode(insert, sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Upsert upsert) {
            renderUpsertNode(upsert, sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Update update) {
            renderUpdateNode(update, sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Delete delete) {
            renderDeleteNode(delete, sql, params, dbType);
            return;
        }
        if (node instanceof SqlNode.Truncate truncate) {
            sql.append("TRUNCATE TABLE ");
            renderNode(truncate.table(), sql, params, dbType);
            return;
        }
        throw new IllegalStateException("Unknown SqlNode: " + node.getClass().getName());
    }

    private static void renderOperand(@Nullable SqlNode operand, String parentOp,
                                      StringBuilder sql, SqlParameterList params, @Nullable DbType dbType) {
        if (operand == null) {
            return;
        }
        boolean wrap = operand instanceof SqlNode.Binary child
                && precedence(child.op()) < precedence(parentOp);
        if (wrap) {
            sql.append('(');
        }
        renderNode(operand, sql, params, dbType);
        if (wrap) {
            sql.append(')');
        }
    }

    private static int precedence(String op) {
        if ("OR".equalsIgnoreCase(op)) {
            return 1;
        }
        if ("AND".equalsIgnoreCase(op)) {
            return 2;
        }
        return 3;
    }

    private static void renderSelectNode(SqlNode.Select select, StringBuilder sql,
                                         SqlParameterList params, @Nullable DbType dbType) {
        String selectPart = renderList(select.projections(), params, dbType);
        String fromPart = renderFragment(select.from(), params, dbType);
        String wherePart = select.where() == null ? null : renderFragment(select.where(), params, dbType);
        String groupByPart = select.groupBy().isEmpty() ? null : renderList(select.groupBy(), params, dbType);
        String havingPart = select.having() == null ? null : renderFragment(select.having(), params, dbType);
        String orderByPart = select.orderBy().isEmpty() ? null : renderList(select.orderBy(), params, dbType);
        sql.append("SELECT ");
        if (select.distinct()) {
            sql.append("DISTINCT ");
        }
        sql.append(selectPart).append(" FROM ").append(fromPart);
        if (notBlank(wherePart)) {
            sql.append(" WHERE ").append(wherePart);
        }
        if (notBlank(groupByPart)) {
            sql.append(" GROUP BY ").append(groupByPart);
        }
        if (notBlank(havingPart)) {
            sql.append(" HAVING ").append(havingPart);
        }
        if (notBlank(orderByPart)) {
            sql.append(" ORDER BY ").append(orderByPart);
        }
        renderLockClause(select.lockMode(), sql, dbType);
    }

    private static void renderLockClause(LockMode lockMode, StringBuilder sql, @Nullable DbType dbType) {
        if (lockMode == null || lockMode == LockMode.NONE) {
            return;
        }
        if (dbType == DbType.sqlite) {
            throw new UnsupportedOperationException("SQLite does not support SELECT ... FOR UPDATE row-level locking");
        }
        switch (lockMode) {
            case FOR_UPDATE -> sql.append(" FOR UPDATE");
            case FOR_UPDATE_NOWAIT -> sql.append(" FOR UPDATE NOWAIT");
            case FOR_UPDATE_SKIP_LOCKED -> sql.append(" FOR UPDATE SKIP LOCKED");
            default -> {
            }
        }
    }

    private static void renderInsertNode(SqlNode.Insert insert, StringBuilder sql,
                                         SqlParameterList params, @Nullable DbType dbType) {
        String table = renderFragment(insert.table(), params, dbType);
        if (insert.columns().isEmpty()) {
            sql.append("INSERT INTO ").append(table).append(" DEFAULT VALUES");
            return;
        }
        sql.append("INSERT INTO ").append(table)
                .append(" (").append(String.join(", ", insert.columns())).append(") VALUES ");
        renderValues(insert.rows(), sql, params, dbType);
    }

    private static void renderUpsertNode(SqlNode.Upsert upsert, StringBuilder sql,
                                         SqlParameterList params, @Nullable DbType dbType) {
        SqlNode.Insert insert = upsert.insert();
        if (upsert.style() == UpsertStyle.MERGE) {
            sql.append("MERGE INTO ");
            renderNode(insert.table(), sql, params, dbType);
            sql.append(" (").append(String.join(", ", insert.columns())).append(") KEY (")
                    .append(upsert.keyColumn()).append(") VALUES ");
            renderValues(insert.rows(), sql, params, dbType);
            return;
        }
        renderInsertNode(insert, sql, params, dbType);
        List<String> updateColumns = upsert.updateColumns();
        if (upsert.style() == UpsertStyle.ON_CONFLICT) {
            if (updateColumns.isEmpty()) {
                sql.append(" ON CONFLICT (").append(upsert.keyColumn()).append(") DO NOTHING");
                return;
            }
            sql.append(" ON CONFLICT (").append(upsert.keyColumn()).append(") DO UPDATE SET ");
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(updateColumns.get(i)).append(" = EXCLUDED.").append(updateColumns.get(i));
            }
            return;
        }
        if (upsert.style() == UpsertStyle.ON_DUPLICATE_KEY) {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            if (updateColumns.isEmpty()) {
                sql.append(upsert.keyColumn()).append(" = ").append(upsert.keyColumn());
                return;
            }
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(updateColumns.get(i)).append(" = VALUES(").append(updateColumns.get(i)).append(')');
            }
            return;
        }
        throw new IllegalStateException("Unsupported upsert style: " + upsert.style());
    }

    private static void renderValues(List<List<SqlNode>> rows, StringBuilder sql,
                                     SqlParameterList params, @Nullable DbType dbType) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                sql.append(", ");
            }
            sql.append('(');
            List<SqlNode> row = rows.get(rowIndex);
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                renderNode(row.get(i), sql, params, dbType);
            }
            sql.append(')');
        }
    }

    private static void renderUpdateNode(SqlNode.Update update, StringBuilder sql,
                                         SqlParameterList params, @Nullable DbType dbType) {
        sql.append("UPDATE ").append(renderFragment(update.table(), params, dbType)).append(" SET ");
        for (int i = 0; i < update.assignments().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            renderNode(update.assignments().get(i), sql, params, dbType);
        }
        if (update.where() != null) {
            sql.append(" WHERE ").append(renderFragment(update.where(), params, dbType));
        }
    }

    private static void renderDeleteNode(SqlNode.Delete delete, StringBuilder sql,
                                         SqlParameterList params, @Nullable DbType dbType) {
        sql.append("DELETE FROM ").append(renderFragment(delete.table(), params, dbType));
        if (delete.where() != null) {
            sql.append(" WHERE ").append(renderFragment(delete.where(), params, dbType));
        }
    }

    private static String renderList(List<SqlNode> nodes, SqlParameterList params, @Nullable DbType dbType) {
        List<String> parts = new ArrayList<>(nodes.size());
        for (SqlNode node : nodes) {
            parts.add(renderFragment(node, params, dbType));
        }
        return String.join(", ", parts);
    }

    private static String renderFragment(SqlNode node, SqlParameterList params, @Nullable DbType dbType) {
        StringBuilder fragment = new StringBuilder();
        renderNode(node, fragment, params, dbType);
        return fragment.toString();
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
