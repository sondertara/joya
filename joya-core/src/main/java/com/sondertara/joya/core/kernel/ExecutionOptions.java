package com.sondertara.joya.core.kernel;

/**
 * 内核执行期配置项（查询超时与批处理分片大小）。
 *
 * @param queryTimeoutSeconds 默认查询超时秒数（{@code <= 0} 表示不限制）
 * @param batchSize           单次 JDBC 批处理分片大小（默认 1000）
 */
public record ExecutionOptions(
        int queryTimeoutSeconds,
        int batchSize
) {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    public static final ExecutionOptions DEFAULT = new ExecutionOptions(0, DEFAULT_BATCH_SIZE);

    public ExecutionOptions {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
    }

    public ExecutionOptions withQueryTimeoutSeconds(int timeoutSeconds) {
        return new ExecutionOptions(timeoutSeconds, batchSize);
    }

    public ExecutionOptions withBatchSize(int newBatchSize) {
        return new ExecutionOptions(queryTimeoutSeconds, newBatchSize);
    }
}
