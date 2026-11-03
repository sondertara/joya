package com.sondertara.joya.core.kernel.observe;

import com.sondertara.joya.core.kernel.support.Ordered;

/**
 * SQL 执行观测扩展点（与改写分离）。
 *
 * <p>{@code SqlInterceptor}/{@code SqlRewriter} 负责改 SQL；本接口只负责看结果：
 * 耗时、行数、生成键、异常。日志、Micrometer、OpenTelemetry、慢查询都接在这里。
 */
public interface SqlExecutionListener extends Ordered {

    default void beforeExecute(SqlExecutionContext ctx) {
    }

    default void afterExecute(SqlExecutionContext ctx, SqlExecutionResult result) {
    }

    default void onError(SqlExecutionContext ctx, Throwable error) {
    }

    /** Stream 生命周期真正结束时触发（与创建时的 afterExecute 区分）。 */
    default void onStreamClosed(SqlExecutionContext ctx) {
    }

    @Override
    default int order() {
        return 0;
    }
}
