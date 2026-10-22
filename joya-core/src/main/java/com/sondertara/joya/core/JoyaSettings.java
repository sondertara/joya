package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionOptions;
import com.sondertara.joya.core.naming.NamingPolicy;

/**
 * 实例级客户端配置（1 {@link JoyaClient} = 1 {@code JoyaSettings}），多数据源下各客户端互不干扰。
 *
 * <p>内部聚合 {@link ExecutionOptions}（内核执行期超时与批大小）与 {@link NamingPolicy}（渲染层大小写与联表上限）。
 *
 * @param queryTimeoutSeconds 默认查询超时秒数（{@code <= 0} 表示不限制）
 * @param batchSize           批量保存/更新分片大小（默认 1000）
 * @param maxJoinTable        联表最大表数限制（默认 10）
 * @param columnUppercase     列名大写开关
 * @param tableUppercase      表名大写开关
 */
public record JoyaSettings(
        int queryTimeoutSeconds,
        int batchSize,
        int maxJoinTable,
        boolean columnUppercase,
        boolean tableUppercase
) {

    public static final int DEFAULT_BATCH_SIZE = ExecutionOptions.DEFAULT_BATCH_SIZE;
    public static final int DEFAULT_MAX_JOIN_TABLE = NamingPolicy.DEFAULT_MAX_JOIN_TABLE;

    public static final JoyaSettings DEFAULT = new JoyaSettings(
            0,
            DEFAULT_BATCH_SIZE,
            DEFAULT_MAX_JOIN_TABLE,
            false,
            false
    );

    public JoyaSettings {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (maxJoinTable <= 0) {
            maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        }
    }

    public static JoyaSettings of(ExecutionOptions executionOptions, NamingPolicy namingPolicy) {
        ExecutionOptions exec = executionOptions == null ? ExecutionOptions.DEFAULT : executionOptions;
        NamingPolicy naming = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
        return new JoyaSettings(
                exec.queryTimeoutSeconds(),
                exec.batchSize(),
                naming.maxJoinTable(),
                naming.columnUppercase(),
                naming.tableUppercase()
        );
    }

    public ExecutionOptions executionOptions() {
        return new ExecutionOptions(queryTimeoutSeconds, batchSize);
    }

    public NamingPolicy namingPolicy() {
        return new NamingPolicy(maxJoinTable, columnUppercase, tableUppercase);
    }

    public JoyaSettings withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return new JoyaSettings(queryTimeoutSeconds, batchSize, maxJoinTable, columnUppercase, tableUppercase);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int queryTimeoutSeconds = 0;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private int maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        private boolean columnUppercase = false;
        private boolean tableUppercase = false;

        private Builder() {
        }

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder maxJoinTable(int maxJoinTable) {
            this.maxJoinTable = maxJoinTable;
            return this;
        }

        public Builder columnUppercase(boolean columnUppercase) {
            this.columnUppercase = columnUppercase;
            return this;
        }

        public Builder tableUppercase(boolean tableUppercase) {
            this.tableUppercase = tableUppercase;
            return this;
        }

        public JoyaSettings build() {
            return new JoyaSettings(
                    queryTimeoutSeconds,
                    batchSize,
                    maxJoinTable,
                    columnUppercase,
                    tableUppercase
            );
        }
    }
}
