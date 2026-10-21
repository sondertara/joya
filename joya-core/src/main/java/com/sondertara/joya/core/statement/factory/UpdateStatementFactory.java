package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.MutationContext;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UPDATE 语句工厂。
 * <p>
 * 关键不变量：<b>SET 参数必须排在 WHERE 参数之前</b>。
 * 参数顺序：SET 列值 → WHERE 条件值。
 * <p>
 * 条件体系统一使用 {@link WhereCriterion}，通过单表 {@link RenderContext#forSingleTable(EntityMetadataContext)}
 * 直接渲染无别名的 WHERE 列名与参数。
 */
public final class UpdateStatementFactory {

    private UpdateStatementFactory() {
    }

    public static SqlStatement create(MutationContext ctx) {
        EntityMetadataContext meta = ctx.metadata();
        WhereCriterion where = ctx.where();

        if ((where == null || where.isEmpty()) && !ctx.allowAll()) {
            throw new IllegalStateException("UPDATE without WHERE is not allowed; use allowAll() explicitly");
        }

        // 1. 确定 SET 列
        List<String> setColumns = ctx.declaredColumns().isEmpty()
                ? meta.updatableColumns()
                : new ArrayList<>(ctx.declaredColumns());

        SqlParameterList params = new SqlParameterList();

        // 2. 先渲染 SET 和 SET 参数
        List<String> finalSetColumns = new ArrayList<>();
        for (String col : setColumns) {
            Object value = resolveValue(ctx, col);
            if (ctx.ignoreNull() && value == null) {
                continue;
            }
            finalSetColumns.add(col);
            params.add(col, value, ParameterSource.SET);
        }
        if (finalSetColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }

        // 3. 再通过单表 RenderContext 直接渲染 WHERE 与 WHERE 参数（排在 SET 之后）
        StringBuilder whereSql = new StringBuilder();
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta);
            where.render(renderContext, whereSql, params, ParameterSource.WHERE);
        }

        // 4. 生成 SQL
        String sql = buildSql(meta.qualifiedTableName(), finalSetColumns, whereSql.toString());

        // 5. 元数据
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(meta.qualifiedTableName()));
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
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
        String field = ctx.metadata().fieldByColumn(columnName);
        return field != null ? values.get(field) : null;
    }

    private static String buildSql(String tableName, List<String> setColumns, String whereSql) {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(tableName).append(" SET ");
        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(setColumns.get(i)).append(" = ?");
        }
        if (whereSql != null && !whereSql.isEmpty()) {
            sb.append(" WHERE ").append(whereSql);
        }
        return sb.toString();
    }
}
