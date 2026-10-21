package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.MutationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * INSERT 语句工厂 —— 吸收旧 {@code InsertSqlCreator} 的成熟规则。
 * <p>
 * 规则：
 * <ul>
 *   <li>确定最终列：显式列集合优先，否则用 {@code insertableColumns()}（排除生成列）。</li>
 *   <li>{@code ignoreNull} 时过滤 null 值。</li>
 *   <li>SQL 列顺序 = 参数顺序 = 元数据列顺序（稳定基础）。</li>
 *   <li>生成列（自增主键）不参与 INSERT。</li>
 * </ul>
 */
public final class InsertStatementFactory {

    private InsertStatementFactory() {
    }

    public static SqlStatement create(MutationContext ctx) {
        EntityMetadataContext meta = ctx.metadata();
        var settings = ctx.settings();

        // 1. 确定最终列
        List<String> columns = ctx.declaredColumns().isEmpty()
                ? meta.insertableColumns()
                : new ArrayList<>(ctx.declaredColumns());

        // 2. 过滤 ignoreNull
        SqlParameterList params = new SqlParameterList();
        List<String> finalColumns = new ArrayList<>();
        for (String col : columns) {
            Object value = resolveValue(ctx, col);
            if (ctx.ignoreNull() && value == null) {
                continue;
            }
            String formattedCol = meta.formatColumn(col, settings);
            finalColumns.add(formattedCol);
            params.add(col, value, ParameterSource.INSERT);
        }

        // 3. 生成 SQL
        String tableName = meta.qualifiedTableName(settings);
        String sql = buildSql(tableName, finalColumns);

        // 4. 元数据：表引用 + 实体类型
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(tableName));
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql, params.toList(), metadata, true);
    }

    private static Object resolveValue(MutationContext ctx, String columnName) {
        Map<String, Object> values = ctx.values();
        if (values.containsKey(columnName)) {
            return values.get(columnName);
        }
        // 尝试字段名匹配
        String field = ctx.metadata().fieldByColumn(columnName);
        return field != null ? values.get(field) : null;
    }

    private static String buildSql(String tableName, List<String> columns) {
        if (columns.isEmpty()) {
            // 全列 null 且 ignoreNull：生成无列 INSERT（数据库默认值）
            return "INSERT INTO " + tableName + " DEFAULT VALUES";
        }
        String colPart = String.join(", ", columns);
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName).append(" (").append(colPart).append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }
}
