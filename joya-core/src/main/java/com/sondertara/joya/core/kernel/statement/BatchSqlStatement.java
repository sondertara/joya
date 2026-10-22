package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.kernel.ExecutionOptions;

import java.util.List;
import java.util.Objects;

/**
 * 批处理语句 —— 一等公民独立契约，不复用单条 {@link SqlStatement} 的逐值参数模型。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>{@code sql} 为 SQL 模板，所有行共用。</li>
 *   <li>{@code columns} 为列级绑定描述，数量 = 列数（如 10），整批共享，不逐值 new。</li>
 *   <li>{@code rows} 为行值，保持裸 {@code Object[]}，零额外包装。</li>
 *   <li>{@code batchSize} 为单 {@code PreparedStatement} 内部分片 Flush 阈值（默认 1000）。</li>
 *   <li>{@code returnGeneratedKeys} 控制是否收集数据库生成的自增主键。</li>
 * </ul>
 *
 * @param sql                 SQL 模板（INSERT/UPDATE/DELETE）
 * @param columns             列级绑定描述（每列一个）
 * @param rows                行值（每行一个 {@code Object[]}，长度 = columns.size()）
 * @param metadata            语句元数据
 * @param batchSize           单次 executeBatch 分片行数阈值（<= 0 时取默认 1000）
 * @param returnGeneratedKeys 是否收集数据库生成的主键
 */
public record BatchSqlStatement(
        String sql,
        List<ColumnBinding> columns,
        List<Object[]> rows,
        StatementMetadata metadata,
        int batchSize,
        boolean returnGeneratedKeys
) {

    public BatchSqlStatement {
        Objects.requireNonNull(sql, "batch sql must not be null");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
        if (batchSize <= 0) {
            batchSize = ExecutionOptions.DEFAULT_BATCH_SIZE;
        }
    }

    public BatchSqlStatement(
            String sql,
            List<ColumnBinding> columns,
            List<Object[]> rows,
            StatementMetadata metadata
    ) {
        this(sql, columns, rows, metadata, ExecutionOptions.DEFAULT_BATCH_SIZE, false);
    }

    /** 便捷构造：仅 SQL + 列描述 + 行值。 */
    public static BatchSqlStatement of(String sql, List<ColumnBinding> columns, List<Object[]> rows) {
        return new BatchSqlStatement(sql, columns, rows, StatementMetadata.of(Operation.BATCH));
    }

    /** 派生替换 SQL 文本的新批处理语句。 */
    public BatchSqlStatement withSql(String newSql) {
        if (sql.equals(newSql)) {
            return this;
        }
        return new BatchSqlStatement(newSql, columns, rows, metadata, batchSize, returnGeneratedKeys);
    }

    /** 派生指定分片 Flush 大小的新批处理语句。 */
    public BatchSqlStatement withBatchSize(int newBatchSize) {
        return new BatchSqlStatement(sql, columns, rows, metadata, newBatchSize, returnGeneratedKeys);
    }

    /** 派生是否收集生成主键的新批处理语句。 */
    public BatchSqlStatement withReturnGeneratedKeys(boolean returnKeys) {
        return new BatchSqlStatement(sql, columns, rows, metadata, batchSize, returnKeys);
    }

    /** 行数。 */
    public int rowCount() {
        return rows.size();
    }

    /** 列数。 */
    public int columnCount() {
        return columns.size();
    }
}
