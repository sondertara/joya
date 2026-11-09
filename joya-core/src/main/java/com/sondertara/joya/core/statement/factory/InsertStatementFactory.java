package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;

import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.statement.MutationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * INSERT 语句工厂（统一覆盖单条 {@link SqlStatement} 与批量 {@link BatchSqlStatement} 构建）。
 */
public final class InsertStatementFactory {

    private InsertStatementFactory() {
    }

    public static SqlStatement create(MutationContext ctx) {
        EntityMetadataContext meta = ctx.metadata();
        NamingPolicy namingPolicy = ctx.namingPolicy();

        // 1. 确定最终列（若未显式声明列但主键值非空，则自动包含显式主键列并遵守 insertable = false）
        String pk = meta.primaryKey();
        Object pkValue = pk != null ? ctx.resolveValue(pk) : null;
        boolean hasExplicitPk = pkValue != null;
        List<String> columns = ctx.declaredColumns().isEmpty()
                ? (hasExplicitPk ? meta.insertableColumnsWithPrimaryKey() : meta.insertableColumns())
                : new ArrayList<>(ctx.declaredColumns());

        requireNonNullPrimaryKey(meta, pk, pkValue);

        // 2. 过滤 ignoreNull 与 insertable = false
        List<SqlNode> rowValues = new ArrayList<>();
        List<String> finalColumns = new ArrayList<>();
        Set<String> nonInsertable = meta.nonInsertableColumns();
        for (String col : columns) {
            String canonical = meta.canonicalColumn(col);
            String checkedColumn = canonical != null ? canonical : col;
            if (meta.generatedColumns().contains(checkedColumn) && !checkedColumn.equals(pk)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is database-generated and cannot be inserted on entity "
                                + meta.entityType().getName());
            }
            if (nonInsertable.contains(checkedColumn)) {
                continue;
            }
            Object value = ctx.resolveValue(col);
            if (ctx.ignoreNull() && value == null) {
                continue;
            }
            finalColumns.add(meta.formatColumn(col, namingPolicy));
            rowValues.add(SqlNodes.param(col, value, ParameterSource.INSERT));
        }
        if (finalColumns.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }

        // 3. 元数据：表引用 + 实体类型 + 主键列名
        String tableName = meta.qualifiedTableName(namingPolicy);
        String formattedPk = pk != null ? meta.formatColumn(pk, namingPolicy) : null;
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.ofQualified(tableName, ""));
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .tables(tables)
                .entityType(meta.entityType())
                .primaryKey(formattedPk)
                .build();

        // 4. AST-lite：由 SqlRenderer 统一渲染，保证文本与参数顺序稳定
        SqlNode.Insert insert = new SqlNode.Insert(
                SqlNodes.table(meta.tableName(), tableName),
                finalColumns,
                List.of(rowValues));
        return SqlRenderer.render(insert, metadata);
    }

    /**
     * 非自增主键必须由调用方赋值：否则会渲染成 {@code INSERT ... (id, ...) VALUES (NULL, ...)}，
     * 最终只在 JDBC 层得到 {@code NULL not allowed for column "id"}，难以定位是漏了 {@code @GeneratedValue}。
     */
    private static void requireNonNullPrimaryKey(EntityMetadataContext meta, String pk, Object pkValue) {
        if (pk == null || pkValue != null || meta.generatedColumns().contains(pk)) {
            return;
        }
        throw new IllegalArgumentException("Primary key '" + pk + "' of " + meta.entityType().getName()
                + " is null and is not annotated with @GeneratedValue; assign it before insert");
    }

    /**
     * 构建批量 INSERT 语句。
     */
    public static BatchSqlStatement createBatch(
            EntityMetadataContext meta,
            List<String> columns,
            List<Object[]> rows,
            NamingPolicy namingPolicy,
            int batchSize,
            boolean returnGeneratedKeys
    ) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }
        String pk = meta.primaryKey();
        for (String col : columns) {
            String canonical = meta.canonicalColumn(col);
            String checkedColumn = canonical != null ? canonical : col;
            if (meta.nonInsertableColumns().contains(checkedColumn)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is marked as insertable=false on entity "
                                + meta.entityType().getName());
            }
            if (meta.generatedColumns().contains(checkedColumn) && !checkedColumn.equals(pk)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is database-generated and cannot be inserted on entity "
                                + meta.entityType().getName());
            }
        }
        List<String> formattedColumns = columns.stream()
                .map(c -> meta.formatColumn(c, namingPolicy))
                .toList();
        String formattedPk = pk != null ? meta.formatColumn(pk, namingPolicy) : null;
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .primaryKey(formattedPk)
                .build();
        List<ColumnBinding> bindings = new ArrayList<>(columns.size());
        for (String col : columns) {
            bindings.add(ColumnBinding.of(col));
        }
        List<SqlNode> templateRow = new ArrayList<>(columns.size());
        for (String col : columns) {
            templateRow.add(SqlNodes.param(col, null, ParameterSource.INSERT));
        }
        SqlNode.Insert ast = new SqlNode.Insert(
                SqlNodes.table(meta.tableName(), tableName),
                formattedColumns,
                List.of(templateRow));
        SqlStatement rendered = SqlRenderer.render(ast, metadata);
        return new BatchSqlStatement(
                rendered.sql(),
                bindings,
                rows,
                metadata,
                batchSize,
                returnGeneratedKeys,
                ast,
                List.of(),
                List.of()
        );
    }
}
