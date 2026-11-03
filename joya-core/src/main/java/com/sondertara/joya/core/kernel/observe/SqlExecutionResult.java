package com.sondertara.joya.core.kernel.observe;

import java.util.List;

/**
 * 一次 SQL 执行的结果摘要。
 *
 * @param affectedRows   影响行数（DML；查询为 -1）
 * @param rowCount       返回行数（查询；DML 为 -1；流式查询无法预知时为 -1）
 * @param batchCounts    批量执行的分片计数（非批量为空数组）
 * @param generatedKeys  生成键（无则为空列表）
 * @param elapsedNanos   执行耗时（纳秒）
 */
public record SqlExecutionResult(
        long affectedRows,
        long rowCount,
        int[] batchCounts,
        List<Object> generatedKeys,
        long elapsedNanos
) {

    public SqlExecutionResult {
        batchCounts = batchCounts == null ? new int[0] : batchCounts.clone();
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
    }

    public static SqlExecutionResult affected(long affectedRows) {
        return new SqlExecutionResult(affectedRows, -1L, new int[0], List.of(), 0L);
    }

    public static SqlExecutionResult rows(long rowCount) {
        return new SqlExecutionResult(-1L, rowCount, new int[0], List.of(), 0L);
    }

    public static SqlExecutionResult batch(int[] counts) {
        return new SqlExecutionResult(-1L, -1L, counts, List.of(), 0L);
    }

    public static SqlExecutionResult insert(long affectedRows, Object generatedKey) {
        return new SqlExecutionResult(affectedRows, -1L, new int[0],
                generatedKey == null ? List.of() : List.of(generatedKey), 0L);
    }

    public SqlExecutionResult withElapsedNanos(long newElapsedNanos) {
        return new SqlExecutionResult(affectedRows, rowCount, batchCounts, generatedKeys, newElapsedNanos);
    }
}
