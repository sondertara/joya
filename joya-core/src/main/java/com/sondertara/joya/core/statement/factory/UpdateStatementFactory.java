package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.SqlRenderer;

import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
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

        SqlParameterList params = new SqlParameterList();
        Set<String> nonUpdatable = meta.nonUpdatableColumns();

        // 2. 先渲染 SET 和 SET 参数
        List<String> finalSetColumns = new ArrayList<>();
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
            finalSetColumns.add(meta.formatColumn(col, namingPolicy));
            params.add(col, value, ParameterSource.SET);
        }
        if (finalSetColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }

        // 3. 再通过单表 RenderContext 直接渲染 WHERE 与 WHERE 参数（排在 SET 之后）
        StringBuilder whereSql = new StringBuilder();
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta, namingPolicy);
            where.render(renderContext, whereSql, params, ParameterSource.WHERE);
        }

        // 4. 统一由 SqlRenderer 生成 SQL
        String tableName = meta.qualifiedTableName(namingPolicy);
        String sql = SqlRenderer.renderUpdate(tableName, finalSetColumns, whereSql.toString());

        // 5. 元数据
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.ofQualified(tableName, ""));
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql, params.toList(), metadata);
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
        for (String col : setColumns) {
            bindings.add(ColumnBinding.of(col));
        }
        bindings.add(ColumnBinding.of(pk));
        return new BatchSqlStatement(
                SqlRenderer.renderUpdate(tableName, formattedSetColumns, formattedPk + " = ?"),
                bindings,
                rows,
                metadata,
                batchSize,
                false
        );
    }
}
