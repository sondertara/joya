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
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * UPDATE 语句工厂（统一覆盖单条 {@link SqlStatement} 与批量 {@link BatchSqlStatement} 构建）。
 * <p>
 * 关键不变量：<b>SET 参数必须排在 WHERE 参数之前</b>。
 * 参数顺序：SET 列值 → WHERE 条件值。
 */
public final class UpdateStatementFactory {

    private UpdateStatementFactory() {
    }

    public static SqlStatement create(MutationContext ctx) {
        EntityMetadataContext meta = ctx.metadata();
        WhereCriterion where = ctx.where();
        NamingPolicy namingPolicy = ctx.namingPolicy();

        if ((where == null || where.isEmpty()) && !ctx.allowAll()) {
            throw new IllegalStateException("UPDATE without WHERE is not allowed; use allowAll() explicitly");
        }

        // 1. 确定 SET 列：显式声明时严格保留调用方声明顺序（如派生更新方法名顺序），避免参数绑定错位
        List<String> setColumns = ctx.declaredColumns().isEmpty()
                ? meta.updatableColumns()
                : new ArrayList<>(ctx.declaredColumns());

        // 2. 收集 SET 赋值（参数顺序：SET 先于 WHERE）
        List<SqlNode.Assignment> assignments = new ArrayList<>();
        Set<String> nonUpdatable = meta.nonUpdatableColumns();
        for (String col : setColumns) {
            String canonical = meta.canonicalColumn(col);
            String checkedColumn = canonical != null ? canonical : col;
            if (nonUpdatable.contains(checkedColumn)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is marked as updatable=false on entity " + meta.entityType().getName());
            }
            if (meta.generatedColumns().contains(checkedColumn)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is database-generated and cannot be updated on entity "
                                + meta.entityType().getName());
            }
            Object value = ctx.resolveValue(col);
            if (ctx.ignoreNull() && value == null) {
                continue;
            }
            SqlNode rhs = (value instanceof SqlNode nodeValue)
                    ? nodeValue
                    : SqlNodes.param(col, value, ParameterSource.SET);
            assignments.add(SqlNodes.assignment(meta.formatColumn(col, namingPolicy), rhs));
        }
        if (assignments.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }

        // 3. WHERE 条件与查询共用同一条 AST 路径；参数由 SqlRenderer 按 SET → WHERE 顺序重排
        SqlNode whereNode = null;
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta, namingPolicy);
            whereNode = where.toNode(renderContext, ParameterSource.WHERE);
        }

        // 4. 元数据
        String tableName = meta.qualifiedTableName(namingPolicy);
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.ofQualified(tableName, ""));
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        // 5. AST-lite：统一渲染
        SqlNode.Update update = new SqlNode.Update(
                SqlNodes.table(meta.tableName(), tableName),
                assignments,
                whereNode);
        return SqlRenderer.render(update, metadata);
    }

    /**
     * 构建按主键批量更新的 {@link BatchSqlStatement}（参数顺序：setColumns 依次排在前，pk 排在最后）。
     */
    public static BatchSqlStatement createBatchByPrimaryKey(
            EntityMetadataContext meta,
            List<String> setColumns,
            String pk,
            List<Object[]> rows,
            NamingPolicy namingPolicy,
            int batchSize
    ) {
        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        for (String col : setColumns) {
            String canonical = meta.canonicalColumn(col);
            String checkedColumn = canonical != null ? canonical : col;
            if (meta.nonUpdatableColumns().contains(checkedColumn)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is marked as updatable=false on entity "
                                + meta.entityType().getName());
            }
            if (meta.generatedColumns().contains(checkedColumn)) {
                throw new IllegalArgumentException(
                        "Column '" + checkedColumn + "' is database-generated and cannot be updated on entity "
                                + meta.entityType().getName());
            }
        }
        List<String> formattedSetColumns = setColumns.stream()
                .map(c -> meta.formatColumn(c, namingPolicy))
                .toList();
        String formattedPk = meta.formatColumn(pk, namingPolicy);
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        List<ColumnBinding> bindings = new ArrayList<>(setColumns.size() + 1);
        List<SqlNode.Assignment> assignments = new ArrayList<>(setColumns.size());
        for (int i = 0; i < setColumns.size(); i++) {
            bindings.add(ColumnBinding.of(setColumns.get(i)));
            assignments.add(SqlNodes.assignment(formattedSetColumns.get(i),
                    SqlNodes.param(setColumns.get(i), null, ParameterSource.SET)));
        }
        bindings.add(ColumnBinding.of(pk));
        SqlNode.Table table = SqlNodes.table(meta.tableName(), tableName);
        SqlNode where = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, formattedPk),
                SqlNodes.param(pk, null, ParameterSource.WHERE));
        SqlNode.Update ast = new SqlNode.Update(table, assignments, where);
        SqlStatement rendered = SqlRenderer.render(ast, metadata);
        return new BatchSqlStatement(
                rendered.sql(),
                bindings,
                rows,
                metadata,
                batchSize,
                false,
                ast,
                List.of(),
                List.of()
        );
    }
}
