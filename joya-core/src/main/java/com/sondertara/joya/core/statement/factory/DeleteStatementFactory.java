package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.naming.NamingPolicy;

import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
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

        SqlNode whereNode = null;
        if (where != null && !where.isEmpty()) {
            RenderContext renderContext = RenderContext.forSingleTable(meta, namingPolicy);
            whereNode = where.toNode(renderContext, ParameterSource.WHERE);
        }

        String tableName = meta.qualifiedTableName(namingPolicy);
        Set<TableRef> tables = new LinkedHashSet<>();
        tables.add(TableRef.ofQualified(tableName, ""));
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .tables(tables)
                .entityType(meta.entityType())
                .build();

        SqlNode.Delete delete = new SqlNode.Delete(
                SqlNodes.table(meta.tableName(), tableName),
                whereNode);
        return SqlRenderer.render(delete, metadata);
    }

    /** 构建 TRUNCATE 语句（表名仍由 NamingPolicy/TableNameResolver 决定）。 */
    public static SqlStatement createTruncate(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        String tableName = meta.qualifiedTableName(namingPolicy);
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.ofQualified(tableName, ""))
                .entityType(meta.entityType())
                .build();
        return SqlRenderer.render(new SqlNode.Truncate(SqlNodes.table(meta.tableName(), tableName)), metadata);
    }
}
