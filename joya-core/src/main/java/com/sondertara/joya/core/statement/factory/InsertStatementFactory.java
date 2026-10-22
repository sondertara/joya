package com.sondertara.joya.core.statement.factory;

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

        // 1. 确定最终列
        List<String> columns = ctx.declaredColumns().isEmpty()
                ? meta.insertableColumns()
                : new ArrayList<>(ctx.declaredColumns());

        // 2. 过滤 ignoreNull
        SqlParameterList params = new SqlParameterList();
        List<String> finalColumns = new ArrayList<>();
        for (String col : columns) {
            Object value = ctx.resolveValue(col);
            if (ctx.ignoreNull() && value == null) {
                continue;
            }
            String formattedCol = meta.formatColumn(col, namingPolicy);
            finalColumns.add(formattedCol);
            params.add(col, value, ParameterSource.INSERT);
        }

        // 3. 统一由 SqlRenderer 生成 SQL
        String tableName = meta.qualifiedTableName(namingPolicy);
        String sql = SqlRenderer.renderInsert(tableName, finalColumns);

        // 4. 元数据：表引用 + 实体类型
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(tableName));
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql, params.toList(), metadata, true);
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
        List<String> formattedColumns = columns.stream()
                .map(c -> meta.formatColumn(c, namingPolicy))
                .toList();
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .table(TableRef.of(tableName))
                .entityType(meta.entityType())
                .build();
        List<ColumnBinding> bindings = new ArrayList<>(columns.size());
        for (String col : columns) {
            bindings.add(ColumnBinding.of(col));
        }
        return new BatchSqlStatement(
                SqlRenderer.renderInsert(tableName, formattedColumns),
                bindings,
                rows,
                metadata,
                batchSize,
                returnGeneratedKeys
        );
    }
}
