package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

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
 * @param ast                 框架生成语句的内部结构树；原生 SQL 为 {@code null}
 * @param extraColumns        改写器追加的列（整批同值）
 * @param extraValues         与 extraColumns 一一对应的值
 */
public record BatchSqlStatement(
        String sql,
        List<ColumnBinding> columns,
        List<Object[]> rows,
        StatementMetadata metadata,
        int batchSize,
        boolean returnGeneratedKeys,
        @Nullable SqlNode ast,
        List<ColumnBinding> extraColumns,
        List<Object> extraValues
) {

    /** 默认分片 Flush 行数（{@code ExecutionOptions.DEFAULT_BATCH_SIZE} 的单一来源）。 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    public BatchSqlStatement {
        Objects.requireNonNull(sql, "batch sql must not be null");
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
        extraColumns = extraColumns == null ? List.of() : List.copyOf(extraColumns);
        extraValues = extraValues == null ? List.of() : List.copyOf(extraValues);
        if (extraColumns.size() != extraValues.size()) {
            throw new IllegalArgumentException("extraColumns and extraValues must have the same size");
        }
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
    }

    /** 派生替换 SQL 文本的新批处理语句；文本改写使 AST 失效。 */
    public BatchSqlStatement withSql(String newSql) {
        if (sql.equals(newSql)) {
            return this;
        }
        return new BatchSqlStatement(newSql, columns, rows, metadata, batchSize, returnGeneratedKeys,
                null, extraColumns, extraValues);
    }

    /** 带 AST 的副本（AST 与 SQL 必须来自同一次渲染）。 */
    public BatchSqlStatement withAst(@Nullable SqlNode newAst) {
        return new BatchSqlStatement(sql, columns, rows, metadata, batchSize, returnGeneratedKeys,
                newAst, extraColumns, extraValues);
    }

    /** 派生替换元数据（如注入 {@code dbType}）的新批处理语句。 */
    public BatchSqlStatement withMetadata(StatementMetadata newMetadata) {
        return new BatchSqlStatement(sql, columns, rows, newMetadata, batchSize, returnGeneratedKeys,
                ast, extraColumns, extraValues);
    }

    /** 从无参数的原生 SQL 模板构造批处理语句。 */
    public static BatchSqlStatement fromTemplate(SqlStatement template, List<Object[]> rows, int batchSize) {
        Objects.requireNonNull(template, "template must not be null");
        if (!template.parameters().isEmpty()) {
            throw new IllegalArgumentException("batch template must not carry parameters; pass them as rows");
        }
        int parameterCount = SqlFormatUtils.countPlaceholders(template.sql(), template.metadata().dbType());
        List<ColumnBinding> cols = new java.util.ArrayList<>(parameterCount);
        for (int i = 0; i < parameterCount; i++) {
            cols.add(ColumnBinding.of("p" + (i + 1)));
        }
        return new BatchSqlStatement(template.sql(), cols, rows == null ? List.of() : rows,
                template.metadata(), batchSize, false, null, List.of(), List.of());
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
