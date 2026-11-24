package com.sondertara.joya.core.kernel.statement;

import java.util.List;
import java.util.Objects;

/**
 * 待编译的结构化批量 AST 语句载体（批量 INSERT / UPDATE / UPSERT 提交给 {@code ExecutionKernel} 的内部契约）。
 *
 * <p>在内核完成方言注入与批量 {@code SqlRewriter} 改写后，由 {@link SqlRenderer} 单趟直接编译为
 * 物理 {@link BatchSqlStatement}（含有序 {@link BatchSlot}），消除上游预渲染与重复槽位扫描。
 *
 * @param ast                 含单行模板槽位（{@link SqlNode.Param.RowSlot}）的结构化 AST 根节点
 * @param columns             输入行 {@code Object[]} 的列级绑定描述
 * @param rows                行值列表（每行长度等于 {@code columns.size()}）
 * @param metadata            语句级元数据
 * @param batchSize           单次 {@code executeBatch} 分片阈值（&lt;= 0 时取默认 1000）
 * @param returnGeneratedKeys 是否收集数据库生成的主键
 */
public record BatchAstStatement(
        SqlNode ast,
        List<ColumnBinding> columns,
        List<Object[]> rows,
        StatementMetadata metadata,
        int batchSize,
        boolean returnGeneratedKeys
) {

    public BatchAstStatement {
        ast = Objects.requireNonNull(ast, "batch ast must not be null");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
        if (batchSize <= 0) {
            batchSize = BatchSqlStatement.DEFAULT_BATCH_SIZE;
        }
    }

    public BatchAstStatement withMetadata(StatementMetadata newMetadata) {
        return new BatchAstStatement(ast, columns, rows, newMetadata, batchSize, returnGeneratedKeys);
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        return columns.size();
    }
}
