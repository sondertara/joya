package com.sondertara.joya.core.statement.factory;

import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.statement.MutationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生 UPSERT 语句工厂：在 {@link InsertStatementFactory} 产出的 INSERT 结构树上包装 {@link SqlNode.Upsert}，
 * 文本仍统一由 {@code SqlRenderer} 渲染（门面层不再自己拼装 AST）。
 */
public final class UpsertStatementFactory {

    private UpsertStatementFactory() {
    }

    /**
     * 构造单条 UPSERT 语句（保留 AST，供 {@code SqlRewriter} 观察）。
     *
     * @param ctx     单条插入上下文（列选择已体现在 {@code ctx.declaredColumns()}）
     * @param columns 参与插入的列（含主键）
     * @param pk      主键列
     * @param style   方言 upsert 风格（不得为 {@link UpsertStyle#NONE}）
     */
    public static SqlStatement create(MutationContext ctx, List<String> columns, String pk, UpsertStyle style) {
        SqlStatement insert = InsertStatementFactory.create(ctx);
        SqlNode.Upsert upsert = wrap(insertNode(insert.ast()), ctx.metadata(), columns, pk,
                ctx.namingPolicy(), style);
        return SqlRenderer.render(upsert, insert.metadata());
    }

    /**
     * 构造批量 UPSERT 语句：沿用 INSERT 批量语句的列绑定与行值，仅将 SQL 文本替换为 UPSERT 渲染结果。
     */
    public static BatchSqlStatement createBatch(EntityMetadataContext meta,
                                                List<String> columns,
                                                List<Object[]> rows,
                                                NamingPolicy namingPolicy,
                                                int batchSize,
                                                String pk,
                                                UpsertStyle style) {
        BatchSqlStatement insertBatch = InsertStatementFactory.createBatch(
                meta, columns, rows, namingPolicy, batchSize, false);
        SqlNode.Upsert upsert = wrap(insertNode(insertBatch.ast()), meta, columns, pk, namingPolicy, style);
        SqlStatement rendered = SqlRenderer.render(upsert, insertBatch.metadata());
        return insertBatch.withSql(rendered.sql()).withAst(upsert);
    }

    private static SqlNode.Insert insertNode(SqlNode ast) {
        if (ast instanceof SqlNode.Insert insert) {
            return insert;
        }
        throw new IllegalStateException("InsertStatementFactory must produce a SqlNode.Insert tree");
    }

    private static SqlNode.Upsert wrap(SqlNode.Insert insert, EntityMetadataContext meta, List<String> columns,
                                       String pk, NamingPolicy namingPolicy, UpsertStyle style) {
        String formattedPk = meta.formatColumn(pk, namingPolicy);
        List<String> updateColumns = new ArrayList<>();
        for (String column : columns) {
            String canonical = java.util.Objects.requireNonNullElse(meta.canonicalColumn(column), column);
            if (canonical.equalsIgnoreCase(pk)
                    || meta.nonUpdatableColumns().contains(canonical)
                    || meta.generatedColumns().contains(canonical)) {
                continue;
            }
            updateColumns.add(meta.formatColumn(column, namingPolicy));
        }
        return new SqlNode.Upsert(insert, formattedPk, updateColumns, style);
    }
}
