package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.naming.NamingPolicy;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 统一单趟流式 SQL 渲染器 —— 全仓唯一把 {@link SqlNode} 结构树编译为物理 SQL 与参数/批槽位的组件。
 *
 * <p>核心特性：
 * <ul>
 *   <li><b>单 {@link StringBuilder} 直写</b>：消除逐子句、逐列的临时字符串与集合拷贝；</li>
 *   <li><b>渲染期统一格式化</b>：列标识符在此处按 {@link NamingPolicy} 统一施加大小写与引号转义，
 *       使 {@code SqlRewriter} 新增的列自动继承客户端命名策略；</li>
 *   <li><b>单条与批量同源单趟编译</b>：在遍历 AST 的同时完成参数收集或 {@link BatchSlot} 槽位编译，
 *       仅对显式 {@link SqlNode.Raw} 片段校验占位符计数，纯 AST 节点零二次词法扫描。</li>
 * </ul>
 */
public final class SqlRenderer {

    private SqlRenderer() {
    }

    /** 按默认命名策略把 AST 编译为不可变 {@link SqlStatement}。 */
    public static SqlStatement render(SqlNode node, StatementMetadata metadata) {
        return render(node, metadata, NamingPolicy.DEFAULT);
    }

    /** 按指定命名策略把 AST 编译为不可变 {@link SqlStatement}。 */
    public static SqlStatement render(SqlNode node, StatementMetadata metadata, @Nullable NamingPolicy namingPolicy) {
        Objects.requireNonNull(node, "node must not be null");
        StatementMetadata effectiveMeta = metadata == null ? StatementMetadata.empty() : metadata;
        NamingPolicy policy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
        DbType dbType = effectiveMeta.dbType();
        StringBuilder sql = new StringBuilder(128);
        SingleParamSink sink = new SingleParamSink(dbType);
        renderNode(node, sql, sink, policy, dbType);
        return SqlStatement.of(sql.toString(), sink.toList(), effectiveMeta);
    }

    /** 按指定命名策略把 {@link AstStatement} 编译为不可变 {@link SqlStatement}。 */
    public static SqlStatement render(AstStatement statement, @Nullable NamingPolicy namingPolicy) {
        Objects.requireNonNull(statement, "statement must not be null");
        return render(statement.ast(), statement.metadata(), namingPolicy);
    }

    /** 按指定命名策略把 {@link BatchAstStatement} 单趟编译为不可变物理 {@link BatchSqlStatement}。 */
    public static BatchSqlStatement render(BatchAstStatement batch, @Nullable NamingPolicy namingPolicy) {
        Objects.requireNonNull(batch, "batch must not be null");
        NamingPolicy policy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
        DbType dbType = batch.metadata().dbType();
        StringBuilder sql = new StringBuilder(128);
        BatchSlotSink sink = new BatchSlotSink(batch.columns(), dbType);
        renderNode(batch.ast(), sql, sink, policy, dbType);
        List<BatchSlot> slots = sink.finish();
        return new BatchSqlStatement(
                sql.toString(),
                batch.columns(),
                batch.rows(),
                batch.metadata(),
                batch.batchSize(),
                batch.returnGeneratedKeys(),
                slots);
    }

    private interface ParamSink {
        void onParam(String name, SqlNode.Param.Binding binding, ParameterSource source);

        void onRaw(SqlNode.Raw raw);
    }

    private static final class SingleParamSink implements ParamSink {
        private final @Nullable DbType dbType;
        private List<SqlParameter> params;

        SingleParamSink(@Nullable DbType dbType) {
            this.dbType = dbType;
        }

        @Override
        public void onParam(String name, SqlNode.Param.Binding binding, ParameterSource source) {
            if (binding instanceof SqlNode.Param.RowSlot slot) {
                throw new IllegalStateException(
                        "Batch template parameter RowSlot(" + slot.rowIndex()
                                + ") must not appear in a single-row SqlStatement");
            }
            Object value = ((SqlNode.Param.Bound) binding).value();
            if (params == null) {
                params = new ArrayList<>(8);
            }
            int idx = params.size() + 1;
            String effectiveName = (name == null || name.isBlank()) ? "p" + idx : name;
            params.add(SqlParameter.of(idx, effectiveName, value, source));
        }

        @Override
        public void onRaw(SqlNode.Raw raw) {
            validateRawPlaceholders(raw, dbType);
            List<SqlParameter> rawParams = raw.parameters();
            if (rawParams.isEmpty()) {
                return;
            }
            if (params == null) {
                params = new ArrayList<>(Math.max(8, rawParams.size()));
            }
            for (SqlParameter p : rawParams) {
                int idx = params.size() + 1;
                params.add(SqlParameter.of(idx, p.name(), p.value(), p.source(), p.binder()));
            }
        }

        List<SqlParameter> toList() {
            return params == null ? List.of() : List.copyOf(params);
        }
    }

    private static final class BatchSlotSink implements ParamSink {
        private final List<ColumnBinding> columns;
        private final boolean[] seenRow;
        private final @Nullable DbType dbType;
        private final List<BatchSlot> slots;
        private int seenCount;

        BatchSlotSink(List<ColumnBinding> columns, @Nullable DbType dbType) {
            this.columns = columns;
            this.seenRow = new boolean[columns.size()];
            this.dbType = dbType;
            this.slots = new ArrayList<>(columns.size() + 2);
        }

        @Override
        public void onParam(String name, SqlNode.Param.Binding binding, ParameterSource source) {
            if (binding instanceof SqlNode.Param.RowSlot slot) {
                int idx = slot.rowIndex();
                int baseCount = columns.size();
                if (idx < 0 || idx >= baseCount) {
                    throw new IllegalStateException(
                            "Batch template referenced out-of-bounds row column index " + idx
                                    + " (column count=" + baseCount + ")");
                }
                if (seenRow[idx]) {
                    throw new IllegalStateException(
                            "Batch template must not duplicate base row parameter '"
                                    + columns.get(idx).name() + "'");
                }
                seenRow[idx] = true;
                seenCount++;
                slots.add(new BatchSlot.Row(idx, columns.get(idx), source));
            } else {
                Object value = ((SqlNode.Param.Bound) binding).value();
                appendConstSlot(name, value, source, null);
            }
        }

        @Override
        public void onRaw(SqlNode.Raw raw) {
            validateRawPlaceholders(raw, dbType);
            for (SqlParameter p : raw.parameters()) {
                appendConstSlot(p.name(), p.value(), p.source(), p.binder());
            }
        }

        private void appendConstSlot(String name, @Nullable Object value, ParameterSource source,
                                     @Nullable ParameterBinder binder) {
            String effectiveName = (name == null || name.isBlank()) ? "const_" + (slots.size() + 1) : name;
            slots.add(new BatchSlot.Const(effectiveName, value, source,
                    Objects.requireNonNullElse(binder, ParameterBinder.DEFAULT)));
        }

        List<BatchSlot> finish() {
            int baseCount = columns.size();
            if (seenCount != baseCount) {
                for (int i = 0; i < baseCount; i++) {
                    if (!seenRow[i]) {
                        throw new IllegalStateException(
                                "Batch template missing base row parameter '"
                                        + columns.get(i).name() + "' at index " + i);
                    }
                }
            }
            return List.copyOf(slots);
        }
    }

    private static void validateRawPlaceholders(SqlNode.Raw raw, @Nullable DbType dbType) {
        int placeholders = SqlFormatUtils.countPlaceholders(raw.sql(), dbType);
        int provided = raw.parameters().size();
        if (placeholders != provided) {
            throw new IllegalArgumentException(
                    "SQL placeholder count (" + placeholders + ") does not match parameter count ("
                            + provided + ") in Raw node: " + raw.sql());
        }
    }

    private static String formatColumn(String columnName, NamingPolicy policy) {
        if ("*".equals(columnName)) {
            return "*";
        }
        return Objects.requireNonNullElse(policy.formatColumn(columnName), columnName);
    }

    private static void renderNode(SqlNode node, StringBuilder sql, ParamSink sink,
                                   NamingPolicy policy, @Nullable DbType dbType) {
        if (node instanceof SqlNode.Raw raw) {
            sql.append(raw.sql());
            sink.onRaw(raw);
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
            sql.append(formatColumn(column.name(), policy));
            return;
        }
        if (node instanceof SqlNode.Param param) {
            sql.append('?');
            sink.onParam(param.name(), param.binding(), param.source());
            return;
        }
        if (node instanceof SqlNode.Func func) {
            sql.append(func.name()).append('(');
            if (func.star()) {
                sql.append('*');
            } else {
                if (func.distinct()) {
                    sql.append("DISTINCT ");
                }
                renderSeparated(func.args(), sql, sink, policy, dbType);
            }
            sql.append(')');
            return;
        }
        if (node instanceof SqlNode.Binary binary) {
            renderOperand(binary.left(), binary.op(), false, sql, sink, policy, dbType);
            sql.append(' ').append(binary.op().symbol()).append(' ');
            renderOperand(binary.right(), binary.op(), true, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Between between) {
            renderNode(between.value(), sql, sink, policy, dbType);
            sql.append(between.negated() ? " NOT BETWEEN " : " BETWEEN ");
            renderNode(between.lower(), sql, sink, policy, dbType);
            sql.append(" AND ");
            renderNode(between.upper(), sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.In in) {
            renderNode(in.value(), sql, sink, policy, dbType);
            sql.append(in.negated() ? " NOT IN (" : " IN (");
            renderSeparated(in.values(), sql, sink, policy, dbType);
            sql.append(')');
            return;
        }
        if (node instanceof SqlNode.Unary unary) {
            renderNode(unary.operand(), sql, sink, policy, dbType);
            sql.append(' ').append(unary.op().sql());
            return;
        }
        if (node instanceof SqlNode.Like like) {
            renderNode(like.value(), sql, sink, policy, dbType);
            sql.append(like.negated() ? " NOT LIKE " : " LIKE ");
            renderNode(like.pattern(), sql, sink, policy, dbType);
            if (like.escaped()) {
                sql.append(dbType != null && dbType.isLikeMysql() ? " ESCAPE '\\\\'" : " ESCAPE '\\'");
            }
            return;
        }
        if (node instanceof SqlNode.Order order) {
            renderNode(order.expression(), sql, sink, policy, dbType);
            sql.append(' ').append(order.direction().name());
            return;
        }
        if (node instanceof SqlNode.Alias alias) {
            renderNode(alias.expression(), sql, sink, policy, dbType);
            if (!alias.alias().isBlank()) {
                sql.append(" AS ").append(alias.alias());
            }
            return;
        }
        if (node instanceof SqlNode.Group group) {
            sql.append('(');
            renderNode(group.body(), sql, sink, policy, dbType);
            sql.append(')');
            return;
        }
        if (node instanceof SqlNode.Assignment assignment) {
            sql.append(formatColumn(assignment.column(), policy)).append(" = ");
            renderNode(assignment.value(), sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.From from) {
            renderNode(from.root(), sql, sink, policy, dbType);
            for (SqlNode.Join join : from.joins()) {
                sql.append(' ').append(join.kind().sql()).append(' ');
                renderNode(join.table(), sql, sink, policy, dbType);
                sql.append(" ON ");
                renderNode(join.on(), sql, sink, policy, dbType);
            }
            return;
        }
        if (node instanceof SqlNode.Join join) {
            sql.append(join.kind().sql()).append(' ');
            renderNode(join.table(), sql, sink, policy, dbType);
            sql.append(" ON ");
            renderNode(join.on(), sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Count count) {
            sql.append("SELECT COUNT(*) FROM (");
            int innerStart = sql.length();
            renderSelectNode(count.select(), false, sql, sink, policy, dbType);
            String alias = SqlFormatUtils.chooseUniqueIdentifier(sql.subSequence(innerStart, sql.length()), "TEMP_T");
            sql.append(") ").append(alias);
            return;
        }
        if (node instanceof SqlNode.Select select) {
            renderSelectNode(select, true, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Insert insert) {
            renderInsertNode(insert, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Upsert upsert) {
            renderUpsertNode(upsert, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Update update) {
            renderUpdateNode(update, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Delete delete) {
            renderDeleteNode(delete, sql, sink, policy, dbType);
            return;
        }
        if (node instanceof SqlNode.Truncate truncate) {
            sql.append("TRUNCATE TABLE ");
            renderNode(truncate.table(), sql, sink, policy, dbType);
            return;
        }
        throw new IllegalStateException("Unknown SqlNode: " + node.getClass().getName());
    }

    private static void renderSeparated(List<SqlNode> nodes, StringBuilder sql, ParamSink sink,
                                        NamingPolicy policy, @Nullable DbType dbType) {
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            renderNode(nodes.get(i), sql, sink, policy, dbType);
        }
    }

    private static void renderOperand(SqlNode operand, SqlNode.BinaryOp parentOp, boolean rightSide,
                                      StringBuilder sql, ParamSink sink,
                                      NamingPolicy policy, @Nullable DbType dbType) {
        boolean wrap = operand instanceof SqlNode.Binary child
                && parentOp.requiresParens(child.op(), rightSide);
        if (wrap) {
            sql.append('(');
        }
        renderNode(operand, sql, sink, policy, dbType);
        if (wrap) {
            sql.append(')');
        }
    }

    private static void renderSelectNode(SqlNode.Select select, boolean includeLock,
                                         StringBuilder sql, ParamSink sink,
                                         NamingPolicy policy, @Nullable DbType dbType) {
        sql.append("SELECT ");
        if (select.distinct()) {
            sql.append("DISTINCT ");
        }
        renderSeparated(select.projections(), sql, sink, policy, dbType);
        sql.append(" FROM ");
        renderNode(select.from(), sql, sink, policy, dbType);
        appendClause(" WHERE ", select.where(), sql, sink, policy, dbType);
        if (!select.groupBy().isEmpty()) {
            sql.append(" GROUP BY ");
            renderSeparated(select.groupBy(), sql, sink, policy, dbType);
        }
        appendClause(" HAVING ", select.having(), sql, sink, policy, dbType);
        if (!select.orderBy().isEmpty()) {
            sql.append(" ORDER BY ");
            renderSeparated(select.orderBy(), sql, sink, policy, dbType);
        }
        if (includeLock) {
            renderLockClause(select.lockMode(), sql, dbType);
        }
    }

    private static void appendClause(String prefix, @Nullable SqlNode clause,
                                     StringBuilder sql, ParamSink sink,
                                     NamingPolicy policy, @Nullable DbType dbType) {
        if (clause == null) {
            return;
        }
        if (clause instanceof SqlNode.Raw raw && raw.sql().isBlank()) {
            return;
        }
        sql.append(prefix);
        renderNode(clause, sql, sink, policy, dbType);
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

    private static void renderInsertNode(SqlNode.Insert insert, StringBuilder sql, ParamSink sink,
                                         NamingPolicy policy, @Nullable DbType dbType) {
        sql.append("INSERT INTO ");
        renderNode(insert.table(), sql, sink, policy, dbType);
        List<String> columns = insert.columns();
        if (columns.isEmpty()) {
            sql.append(" DEFAULT VALUES");
            return;
        }
        sql.append(" (");
        appendFormattedColumns(columns, sql, policy);
        sql.append(") VALUES ");
        renderValues(insert.rows(), sql, sink, policy, dbType);
    }

    private static void appendFormattedColumns(List<String> columns, StringBuilder sql, NamingPolicy policy) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(formatColumn(columns.get(i), policy));
        }
    }

    private static void renderUpsertNode(SqlNode.Upsert upsert, StringBuilder sql, ParamSink sink,
                                         NamingPolicy policy, @Nullable DbType dbType) {
        SqlNode.Insert insert = upsert.insert();
        String keyCol = formatColumn(upsert.keyColumn(), policy);
        if (upsert.style() == UpsertStyle.MERGE) {
            sql.append("MERGE INTO ");
            renderNode(insert.table(), sql, sink, policy, dbType);
            sql.append(" (");
            appendFormattedColumns(insert.columns(), sql, policy);
            sql.append(") KEY (").append(keyCol).append(") VALUES ");
            renderValues(insert.rows(), sql, sink, policy, dbType);
            return;
        }
        renderInsertNode(insert, sql, sink, policy, dbType);
        List<String> updateColumns = upsert.updateColumns();
        if (upsert.style() == UpsertStyle.ON_CONFLICT) {
            if (updateColumns.isEmpty()) {
                sql.append(" ON CONFLICT (").append(keyCol).append(") DO NOTHING");
                return;
            }
            sql.append(" ON CONFLICT (").append(keyCol).append(") DO UPDATE SET ");
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = formatColumn(updateColumns.get(i), policy);
                sql.append(col).append(" = EXCLUDED.").append(col);
            }
            return;
        }
        if (upsert.style() == UpsertStyle.ON_DUPLICATE_KEY) {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            if (updateColumns.isEmpty()) {
                sql.append(keyCol).append(" = ").append(keyCol);
                return;
            }
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                String col = formatColumn(updateColumns.get(i), policy);
                sql.append(col).append(" = VALUES(").append(col).append(')');
            }
            return;
        }
        throw new IllegalStateException("Unsupported upsert style: " + upsert.style());
    }

    private static void renderValues(List<List<SqlNode>> rows, StringBuilder sql, ParamSink sink,
                                     NamingPolicy policy, @Nullable DbType dbType) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            if (rowIndex > 0) {
                sql.append(", ");
            }
            sql.append('(');
            renderSeparated(rows.get(rowIndex), sql, sink, policy, dbType);
            sql.append(')');
        }
    }

    private static void renderUpdateNode(SqlNode.Update update, StringBuilder sql, ParamSink sink,
                                         NamingPolicy policy, @Nullable DbType dbType) {
        sql.append("UPDATE ");
        renderNode(update.table(), sql, sink, policy, dbType);
        sql.append(" SET ");
        List<SqlNode.Assignment> assignments = update.assignments();
        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            renderNode(assignments.get(i), sql, sink, policy, dbType);
        }
        appendClause(" WHERE ", update.where(), sql, sink, policy, dbType);
    }

    private static void renderDeleteNode(SqlNode.Delete delete, StringBuilder sql, ParamSink sink,
                                         NamingPolicy policy, @Nullable DbType dbType) {
        sql.append("DELETE FROM ");
        renderNode(delete.table(), sql, sink, policy, dbType);
        appendClause(" WHERE ", delete.where(), sql, sink, policy, dbType);
    }
}
