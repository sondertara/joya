package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.TableScope;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 实体级 DQL/DML 结构化 AST 组装器（包内叶节点，替代原 {@code core.statement} 与 5 个 {@code *StatementFactory}）。
 *
 * <p>核心原则：
 * <ul>
 *   <li>只产出未渲染的 {@link AstStatement} / {@link BatchAstStatement}，完全不调用 {@code SqlRenderer}；</li>
 *   <li>所有列名在 AST 与 {@link StatementMetadata#primaryKey()} 中均保留未加引号规范名，由渲染器单点格式化；</li>
 *   <li>严禁为表名或 SELECT 投影使用 {@link SqlNode.Raw}。</li>
 * </ul>
 */
final class EntityStatements {

    private EntityStatements() {
    }

    static AstStatement insertAst(EntityMetadataContext meta,
                                  List<String> columns,
                                  List<@Nullable Object> values,
                                  NamingPolicy namingPolicy) {
        validateInsertColumns(meta, columns, false);
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("Insert column count (" + columns.size()
                    + ") does not match value count (" + values.size() + ")");
        }
        List<SqlNode> rowNodes = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            String col = canonical(meta, columns.get(i));
            rowNodes.add(SqlNodes.param(col, values.get(i), ParameterSource.INSERT));
        }
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .primaryKey(meta.primaryKey())
                .build();
        SqlNode.Insert insert = new SqlNode.Insert(
                SqlNodes.table(meta.tableName(), tableName),
                canonicalList(meta, columns),
                List.of(rowNodes));
        return new AstStatement(insert, metadata);
    }

    static BatchAstStatement insertBatchAst(EntityMetadataContext meta,
                                            List<String> columns,
                                            List<Object[]> rows,
                                            NamingPolicy namingPolicy,
                                            int batchSize,
                                            boolean returnGeneratedKeys) {
        validateInsertColumns(meta, columns, true);
        List<String> canonicalCols = canonicalList(meta, columns);
        List<ColumnBinding> bindings = new ArrayList<>(canonicalCols.size());
        List<SqlNode> templateRow = new ArrayList<>(canonicalCols.size());
        for (int i = 0; i < canonicalCols.size(); i++) {
            String col = canonicalCols.get(i);
            bindings.add(ColumnBinding.of(col));
            templateRow.add(SqlNodes.rowParam(col, i, ParameterSource.INSERT));
        }
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .primaryKey(meta.primaryKey())
                .build();
        SqlNode.Insert ast = new SqlNode.Insert(
                SqlNodes.table(meta.tableName(), tableName),
                canonicalCols,
                List.of(templateRow));
        return new BatchAstStatement(ast, bindings, rows, metadata, batchSize, returnGeneratedKeys);
    }

    static AstStatement upsertAst(EntityMetadataContext meta,
                                  List<String> columns,
                                  List<@Nullable Object> values,
                                  String pk,
                                  UpsertStyle style,
                                  @Nullable DbType dbType,
                                  NamingPolicy namingPolicy) {
        AstStatement insertStmt = insertAst(meta, columns, values, namingPolicy);
        SqlNode.Insert insertNode = (SqlNode.Insert) insertStmt.ast();
        SqlNode.Upsert upsert = wrapUpsert(insertNode, meta, insertNode.columns(), pk, style);
        StatementMetadata metadata = dbType != null
                ? insertStmt.metadata().withDbType(dbType)
                : insertStmt.metadata();
        return new AstStatement(upsert, metadata);
    }

    static BatchAstStatement upsertBatchAst(EntityMetadataContext meta,
                                            List<String> columns,
                                            List<Object[]> rows,
                                            NamingPolicy namingPolicy,
                                            int batchSize,
                                            String pk,
                                            UpsertStyle style,
                                            @Nullable DbType dbType) {
        BatchAstStatement insertBatch = insertBatchAst(meta, columns, rows, namingPolicy, batchSize, false);
        SqlNode.Insert insertNode = (SqlNode.Insert) insertBatch.ast();
        SqlNode.Upsert upsert = wrapUpsert(insertNode, meta, insertNode.columns(), pk, style);
        StatementMetadata metadata = dbType != null
                ? insertBatch.metadata().withDbType(dbType)
                : insertBatch.metadata();
        return new BatchAstStatement(upsert, insertBatch.columns(), insertBatch.rows(), metadata,
                insertBatch.batchSize(), false);
    }

    private static SqlNode.Upsert wrapUpsert(SqlNode.Insert insert,
                                             EntityMetadataContext meta,
                                             List<String> canonicalColumns,
                                             String pk,
                                             UpsertStyle style) {
        String canonicalPk = canonical(meta, pk);
        List<String> updateColumns = new ArrayList<>(canonicalColumns.size());
        for (String col : canonicalColumns) {
            if (col.equalsIgnoreCase(canonicalPk)
                    || meta.nonUpdatableColumns().contains(col)
                    || meta.generatedColumns().contains(col)) {
                continue;
            }
            updateColumns.add(col);
        }
        return new SqlNode.Upsert(insert, canonicalPk, updateColumns, style);
    }

    static AstStatement updateByPkAst(EntityMetadataContext meta,
                                      List<SqlNode.Assignment> assignments,
                                      String pk,
                                      Object pkValue,
                                      NamingPolicy namingPolicy) {
        return updateByPkAst(meta, assignments, pk, pkValue, null, namingPolicy);
    }

    static AstStatement updateByPkAst(EntityMetadataContext meta,
                                      List<SqlNode.Assignment> assignments,
                                      String pk,
                                      Object pkValue,
                                      @Nullable SqlNode rowGuard,
                                      NamingPolicy namingPolicy) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        String canonicalPk = canonical(meta, pk);
        SqlNode pkEq = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, canonicalPk),
                SqlNodes.param(canonicalPk, pkValue, ParameterSource.WHERE));
        SqlNode whereNode = rowGuard == null ? pkEq : SqlNodes.and(pkEq, rowGuard);
        return buildUpdateAst(meta, assignments, whereNode, namingPolicy);
    }

    static AstStatement updateAst(EntityMetadataContext meta,
                                  List<SqlNode.Assignment> assignments,
                                  @Nullable WhereCriterion where,
                                  boolean allowAll,
                                  NamingPolicy namingPolicy) {
        if ((where == null || where.isEmpty()) && !allowAll) {
            throw new IllegalStateException("UPDATE without WHERE is not allowed; use allowAll() explicitly");
        }
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        SqlNode whereNode = null;
        if (where != null && !where.isEmpty()) {
            TableScope scope = TableScope.forSingleTable(meta, namingPolicy);
            whereNode = where.toNode(scope, ParameterSource.WHERE);
        }
        return buildUpdateAst(meta, assignments, whereNode, namingPolicy);
    }

    private static AstStatement buildUpdateAst(EntityMetadataContext meta,
                                               List<SqlNode.Assignment> assignments,
                                               @Nullable SqlNode whereNode,
                                               NamingPolicy namingPolicy) {
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        SqlNode.Update update = new SqlNode.Update(
                SqlNodes.table(meta.tableName(), tableName),
                assignments,
                whereNode);
        return new AstStatement(update, metadata);
    }

    static BatchAstStatement updateBatchByPkAst(EntityMetadataContext meta,
                                                List<String> setColumns,
                                                String pk,
                                                List<Object[]> rows,
                                                NamingPolicy namingPolicy,
                                                int batchSize) {
        return updateBatchByPkAst(meta, setColumns, pk, null, rows, namingPolicy, batchSize);
    }

    static BatchAstStatement updateBatchByPkAst(EntityMetadataContext meta,
                                                List<String> setColumns,
                                                String pk,
                                                @Nullable String extraWhereColumn,
                                                List<Object[]> rows,
                                                NamingPolicy namingPolicy,
                                                int batchSize) {
        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        validateUpdateColumns(meta, setColumns);
        List<String> canonicalSetCols = canonicalList(meta, setColumns);
        String canonicalPk = canonical(meta, pk);
        List<ColumnBinding> bindings = new ArrayList<>(canonicalSetCols.size() + (extraWhereColumn != null ? 2 : 1));
        List<SqlNode.Assignment> assignments = new ArrayList<>(canonicalSetCols.size());
        for (int i = 0; i < canonicalSetCols.size(); i++) {
            String col = canonicalSetCols.get(i);
            bindings.add(ColumnBinding.of(col));
            assignments.add(SqlNodes.assignment(col,
                    SqlNodes.rowParam(col, i, ParameterSource.SET)));
        }
        int pkIndex = canonicalSetCols.size();
        bindings.add(ColumnBinding.of(canonicalPk));
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        SqlNode.Table table = SqlNodes.table(meta.tableName(), tableName);
        SqlNode where = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, canonicalPk),
                SqlNodes.rowParam(canonicalPk, pkIndex, ParameterSource.WHERE));
        if (extraWhereColumn != null) {
            String canonicalExtra = canonical(meta, extraWhereColumn);
            int extraIndex = pkIndex + 1;
            bindings.add(ColumnBinding.of(canonicalExtra));
            SqlNode extraEq = SqlNodes.eq(
                    SqlNodes.column((SqlNode.Table) null, canonicalExtra),
                    SqlNodes.rowParam(canonicalExtra, extraIndex, ParameterSource.WHERE));
            where = SqlNodes.and(where, extraEq);
        }
        SqlNode.Update ast = new SqlNode.Update(table, assignments, where);
        return new BatchAstStatement(ast, bindings, rows, metadata, batchSize, false);
    }

    static AstStatement deleteByPkAst(EntityMetadataContext meta,
                                      String pk,
                                      Object pkValue,
                                      @Nullable SqlNode rowGuard,
                                      NamingPolicy namingPolicy) {
        String canonicalPk = canonical(meta, pk);
        SqlNode pkEq = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, canonicalPk),
                SqlNodes.param(canonicalPk, pkValue, ParameterSource.WHERE));
        SqlNode whereNode = rowGuard == null ? pkEq : SqlNodes.and(pkEq, rowGuard);
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        SqlNode.Delete delete = new SqlNode.Delete(SqlNodes.table(meta.tableName(), tableName), whereNode);
        return new AstStatement(delete, metadata);
    }

    static BatchAstStatement deleteBatchByPkAst(EntityMetadataContext meta,
                                                String pk,
                                                @Nullable String extraWhereColumn,
                                                List<Object[]> rows,
                                                NamingPolicy namingPolicy,
                                                int batchSize) {
        String canonicalPk = canonical(meta, pk);
        List<ColumnBinding> bindings = new ArrayList<>(extraWhereColumn != null ? 2 : 1);
        bindings.add(ColumnBinding.of(canonicalPk));
        SqlNode where = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, canonicalPk),
                SqlNodes.rowParam(canonicalPk, 0, ParameterSource.WHERE));
        if (extraWhereColumn != null) {
            String canonicalExtra = canonical(meta, extraWhereColumn);
            bindings.add(ColumnBinding.of(canonicalExtra));
            SqlNode extraEq = SqlNodes.eq(
                    SqlNodes.column((SqlNode.Table) null, canonicalExtra),
                    SqlNodes.rowParam(canonicalExtra, 1, ParameterSource.WHERE));
            where = SqlNodes.and(where, extraEq);
        }
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        SqlNode.Delete delete = new SqlNode.Delete(SqlNodes.table(meta.tableName(), tableName), where);
        return new BatchAstStatement(delete, bindings, rows, metadata, batchSize, false);
    }

    static AstStatement deleteAst(EntityMetadataContext meta,
                                  @Nullable WhereCriterion where,
                                  boolean allowAll,
                                  NamingPolicy namingPolicy) {
        if ((where == null || where.isEmpty()) && !allowAll) {
            throw new IllegalStateException("DELETE without WHERE is not allowed; use allowAll() explicitly");
        }
        SqlNode whereNode = null;
        if (where != null && !where.isEmpty()) {
            TableScope scope = TableScope.forSingleTable(meta, namingPolicy);
            whereNode = where.toNode(scope, ParameterSource.WHERE);
        }
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        SqlNode.Delete delete = new SqlNode.Delete(SqlNodes.table(meta.tableName(), tableName), whereNode);
        return new AstStatement(delete, metadata);
    }

    static AstStatement truncateAst(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.TRUNCATE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        return new AstStatement(new SqlNode.Truncate(SqlNodes.table(meta.tableName(), tableName)), metadata);
    }

    static AstStatement selectColumnsAst(EntityMetadataContext meta,
                                         @Nullable WhereCriterion where,
                                         @Nullable Set<String> declaredColumns,
                                         NamingPolicy namingPolicy) {
        TableScope scope = TableScope.forAliasedSingleTable(meta, namingPolicy);
        SqlNode.From fromNode = scope.toFromNode();
        SqlNode.Table rootTable = fromNode.root();
        List<SqlNode> projections = new ArrayList<>();
        if (declaredColumns == null || declaredColumns.isEmpty()) {
            projections.add(SqlNodes.column((SqlNode.Table) null, "*"));
        } else {
            Set<String> canonicalDeclared = new LinkedHashSet<>(declaredColumns.size());
            for (String col : declaredColumns) {
                canonicalDeclared.add(meta.resolveColumnName(col));
            }
            for (String col : meta.columnNames()) {
                if (canonicalDeclared.contains(col)) {
                    projections.add(SqlNodes.column(rootTable, col));
                }
            }
        }
        SqlNode whereNode = (where != null && !where.isEmpty())
                ? where.toNode(scope, ParameterSource.WHERE)
                : null;
        StatementMetadata metadata = StatementMetadata.builder(Operation.SELECT)
                .tables(scope.extractTables())
                .entityType(meta.entityType())
                .build();
        SqlNode.Select select = new SqlNode.Select(
                false,
                projections,
                fromNode,
                whereNode,
                List.of(),
                null,
                List.of());
        return new AstStatement(select, metadata);
    }

    static void validateInsertColumns(EntityMetadataContext meta, List<String> columns, boolean rejectNonInsertable) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }
        for (String col : columns) {
            meta.requireInsertableColumn(col, rejectNonInsertable);
        }
    }

    static void validateUpdateColumns(EntityMetadataContext meta, Iterable<String> columns) {
        for (String col : columns) {
            meta.requireUpdatableColumn(col);
        }
    }

    private static String canonical(EntityMetadataContext meta, String col) {
        return Objects.requireNonNullElse(meta.canonicalColumn(col), col);
    }

    private static List<String> canonicalList(EntityMetadataContext meta, List<String> columns) {
        List<String> list = new ArrayList<>(columns.size());
        for (String col : columns) {
            list.add(canonical(meta, col));
        }
        return list;
    }
}
