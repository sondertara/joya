package com.sondertara.joya.core.statement.factory;

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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DELETE 语句工厂。
 * <p>
 * 安全规则：无 WHERE 条件且未显式 allowAll 时拒绝执行。
 * <p>
 * 条件体系统一使用 {@link WhereCriterion}，通过单表 {@link RenderContext#forSingleTable(EntityMetadataContext)}
 * 直接渲染无别名的 WHERE 列名与参数。
 */
public final class DeleteStatementFactory {

    private DeleteStatementFactory() {
    }

    public static SqlStatement create(MutationContext ctx) {
        EntityMetadataContext meta = ctx.metadata();
        WhereCriterion where = ctx.where();
        var namingPolicy = ctx.namingPolicy();

        if ((where == null || where.isEmpty()) && !ctx.allowAll()) {
            throw new IllegalStateException("DELETE without WHERE is not allowed; use allowAll() explicitly");
        }

        SqlParameterList params = new SqlParameterList();
        StringBuilder whereSql = new StringBuilder();
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta, namingPolicy);
            where.render(renderContext, whereSql, params, ParameterSource.WHERE);
        }

        String tableName = meta.qualifiedTableName(namingPolicy);
        String sql = SqlRenderer.renderDelete(tableName, whereSql.toString());

        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.of(tableName));
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        return SqlStatement.of(sql, params.toList(), metadata, true);
    }
}
