package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 批处理语句 —— 一等公民独立契约，不复用单条 {@link SqlStatement} 的逐值参数模型。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>{@code sql} 为 SQL 模板，所有行共用。</li>
 *   <li>{@code columns} 为输入行 {@code Object[]} 的列级描述，整批共享，不逐值 new。</li>
 *   <li>{@code rows} 为行值，保持裸 {@code Object[]}，零额外包装。</li>
 *   <li>{@code slots} 为按最终 SQL {@code ?} 顺序排列的占位符绑定槽位（{@link BatchSlot.Row} 与 {@link BatchSlot.Const} 可任意交错）。</li>
 *   <li>{@code batchSize} 为单 {@code PreparedStatement} 内部分片 Flush 阈值（默认 1000）。</li>
 *   <li>{@code returnGeneratedKeys} 控制是否收集数据库生成的自增主键。</li>
 * </ul>
 *
 * @param sql                 SQL 模板（INSERT/UPDATE/DELETE）
 * @param columns             输入行的列级绑定描述（每列一个）
 * @param rows                行值（每行一个 {@code Object[]}，长度 = columns.size()）
 * @param metadata            语句元数据
 * @param batchSize           单次 executeBatch 分片行数阈值（&lt;= 0 时取默认 1000）
 * @param returnGeneratedKeys 是否收集数据库生成的主键
 * @param ast                 框架生成语句的内部结构树；原生 SQL 为 {@code null}
 * @param slots               与 SQL {@code ?} 一一对应的绑定槽位列表（传 {@code null} 时按 {@code columns} 顺序默认生成）
 */
public record BatchSqlStatement(
        String sql,
        List<ColumnBinding> columns,
        List<Object[]> rows,
        StatementMetadata metadata,
        int batchSize,
        boolean returnGeneratedKeys,
        @Nullable SqlNode ast,
        List<BatchSlot> slots
) {

    /** 默认分片 Flush 行数（{@code ExecutionOptions.DEFAULT_BATCH_SIZE} 的单一来源）。 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    public BatchSqlStatement {
        Objects.requireNonNull(sql, "batch sql must not be null");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
        slots = slots == null ? defaultSlots(columns) : List.copyOf(slots);
        for (BatchSlot slot : slots) {
            if (slot instanceof BatchSlot.Row rowSlot && rowSlot.rowIndex() >= columns.size()) {
                throw new IllegalArgumentException("BatchSlot.Row index (" + rowSlot.rowIndex()
                        + ") exceeds column count (" + columns.size() + ")");
            }
        }
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
    }

    private static List<BatchSlot> defaultSlots(List<ColumnBinding> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        List<BatchSlot> result = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            result.add(new BatchSlot.Row(i, columns.get(i), ParameterSource.RAW));
        }
        return List.copyOf(result);
    }

    /** 派生替换 SQL 文本的新批处理语句；纯文本改写使 AST 失效，并校验占位符数与绑定槽位一致。 */
    public BatchSqlStatement withSql(String newSql) {
        Objects.requireNonNull(newSql, "batch sql must not be null");
        if (sql.equals(newSql)) {
            return this;
        }
        int placeholders = SqlFormatUtils.countPlaceholders(newSql, metadata.dbType());
        if (placeholders != slots.size()) {
            throw new IllegalArgumentException(
                    "Batch SQL placeholder count (" + placeholders + ") does not match slot count ("
                            + slots.size() + "): " + newSql);
        }
        return new BatchSqlStatement(newSql, columns, rows, metadata, batchSize, returnGeneratedKeys,
                null, slots);
    }

    /** 带 AST 的副本（AST 与 SQL 必须来自同一次渲染）。 */
    public BatchSqlStatement withAst(@Nullable SqlNode newAst) {
        return new BatchSqlStatement(sql, columns, rows, metadata, batchSize, returnGeneratedKeys,
                newAst, slots);
    }

    /** 派生替换元数据（如注入 {@code dbType}）的新批处理语句。 */
    public BatchSqlStatement withMetadata(StatementMetadata newMetadata) {
        return new BatchSqlStatement(sql, columns, rows, newMetadata, batchSize, returnGeneratedKeys,
                ast, slots);
    }

    /** 从无参数的原生 SQL 模板构造批处理语句。 */
    public static BatchSqlStatement fromTemplate(SqlStatement template, List<Object[]> rows, int batchSize) {
        Objects.requireNonNull(template, "template must not be null");
        if (!template.parameters().isEmpty()) {
            throw new IllegalArgumentException("batch template must not carry parameters; pass them as rows");
        }
        int parameterCount = SqlFormatUtils.countPlaceholders(template.sql(), template.metadata().dbType());
        List<ColumnBinding> cols = new ArrayList<>(parameterCount);
        for (int i = 0; i < parameterCount; i++) {
            cols.add(ColumnBinding.of("p" + (i + 1)));
        }
        return new BatchSqlStatement(template.sql(), cols, rows == null ? List.of() : rows,
                template.metadata(), batchSize, false, null, defaultSlots(cols));
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
