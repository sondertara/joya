package com.sondertara.joya.core;

import com.sondertara.joya.core.constant.SchemaValidation;

import java.util.Objects;

/**
 * 实例级客户端配置（1 {@link JoyaClient} = 1 {@code JoyaSettings}），多数据源下各客户端互不干扰。
 *
 * @param queryTimeoutSeconds 默认查询超时秒数（{@code <= 0} 表示不限制）
 * @param batchSize           批量保存/更新分片大小（默认 8000）
 * @param maxJoinTable        联表最大表数限制（默认 10）
 * @param columnUppercase     列名大写开关
 * @param tableUppercase      表名大写开关
 * @param schemaValidation    Schema 校验策略（默认 {@link SchemaValidation#NONE}）
 */
public record JoyaSettings(
        int queryTimeoutSeconds,
        int batchSize,
        int maxJoinTable,
        boolean columnUppercase,
        boolean tableUppercase,
        SchemaValidation schemaValidation
) {

    public static final int DEFAULT_BATCH_SIZE = 8000;
    public static final int DEFAULT_MAX_JOIN_TABLE = 10;

    public static final JoyaSettings DEFAULT = new JoyaSettings(
            0,
            DEFAULT_BATCH_SIZE,
            DEFAULT_MAX_JOIN_TABLE,
            false,
            false,
            SchemaValidation.NONE
    );

    public JoyaSettings {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (maxJoinTable <= 0) {
            maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        }
        schemaValidation = Objects.requireNonNullElse(schemaValidation, SchemaValidation.NONE);
    }

    public JoyaSettings withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return new JoyaSettings(queryTimeoutSeconds, batchSize, maxJoinTable, columnUppercase, tableUppercase, schemaValidation);
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
        private SchemaValidation schemaValidation = SchemaValidation.NONE;

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

        public Builder schemaValidation(SchemaValidation schemaValidation) {
            this.schemaValidation = Objects.requireNonNullElse(schemaValidation, SchemaValidation.NONE);
            return this;
        }

        public JoyaSettings build() {
            return new JoyaSettings(
                    queryTimeoutSeconds,
                    batchSize,
                    maxJoinTable,
                    columnUppercase,
                    tableUppercase,
                    schemaValidation
            );
        }
    }
}
