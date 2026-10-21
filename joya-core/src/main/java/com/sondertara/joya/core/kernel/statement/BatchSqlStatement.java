package com.sondertara.joya.core.kernel.statement;

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
 *   <li>10 万行 × 10 列场景：仅 10 个 {@link ColumnBinding} 实例，而非 100 万个参数对象。</li>
 * </ul>
 *
 * @param sql       SQL 模板（INSERT/UPDATE/DELETE）
 * @param columns   列级绑定描述（每列一个）
 * @param rows      行值（每行一个 {@code Object[]}，长度 = columns.size()）
 * @param metadata  语句元数据
 */
public record BatchSqlStatement(
        String sql,
        List<ColumnBinding> columns,
        List<Object[]> rows,
        StatementMetadata metadata
) {

    public BatchSqlStatement {
        Objects.requireNonNull(sql, "batch sql must not be null");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
    }

    /** 便捷构造：仅 SQL + 列描述 + 行值。 */
    public static BatchSqlStatement of(String sql, List<ColumnBinding> columns, List<Object[]> rows) {
        return new BatchSqlStatement(sql, columns, rows, StatementMetadata.of(Operation.BATCH));
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
