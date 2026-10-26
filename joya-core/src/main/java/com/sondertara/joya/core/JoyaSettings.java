package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionOptions;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.naming.TableNameResolver;

import java.util.Objects;

/**
 * 实例级客户端配置（1 {@link JoyaClient} = 1 {@code JoyaSettings}），多数据源下各客户端互不干扰。
 *
 * <p>由两个语义独立的配置对象组合而成，不再扁平复制字段：
 * <ul>
 *   <li>{@link ExecutionOptions}：执行期行为（查询超时、批处理分片大小）</li>
 *   <li>{@link NamingPolicy}：SQL 渲染命名（大小写、联表上限、动态表名解析）</li>
 * </ul>
 */
public record JoyaSettings(
        ExecutionOptions executionOptions,
        NamingPolicy namingPolicy
) {

    public static final int DEFAULT_BATCH_SIZE = ExecutionOptions.DEFAULT_BATCH_SIZE;
    public static final int DEFAULT_MAX_JOIN_TABLE = NamingPolicy.DEFAULT_MAX_JOIN_TABLE;

    public static final JoyaSettings DEFAULT =
            new JoyaSettings(ExecutionOptions.DEFAULT, NamingPolicy.DEFAULT);

    public JoyaSettings {
        executionOptions = Objects.requireNonNullElse(executionOptions, ExecutionOptions.DEFAULT);
        namingPolicy = Objects.requireNonNullElse(namingPolicy, NamingPolicy.DEFAULT);
    }

    // ===== 便捷委托访问器 =====

    public int queryTimeoutSeconds() {
        return executionOptions.queryTimeoutSeconds();
    }

    public int batchSize() {
        return executionOptions.batchSize();
    }

    public int maxJoinTable() {
        return namingPolicy.maxJoinTable();
    }

    public boolean columnUppercase() {
        return namingPolicy.columnUppercase();
    }

    public boolean tableUppercase() {
        return namingPolicy.tableUppercase();
    }

    public static JoyaSettings of(ExecutionOptions executionOptions, NamingPolicy namingPolicy) {
        return new JoyaSettings(executionOptions, namingPolicy);
    }

    public JoyaSettings withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return new JoyaSettings(
                executionOptions.withQueryTimeoutSeconds(queryTimeoutSeconds),
                namingPolicy
        );
    }

    public JoyaSettings withNamingPolicy(NamingPolicy newNamingPolicy) {
        return new JoyaSettings(executionOptions, newNamingPolicy);
    }

    public JoyaSettings withTableNameResolver(TableNameResolver tableNameResolver) {
        return withNamingPolicy(new NamingPolicy(
                namingPolicy.maxJoinTable(),
                namingPolicy.columnUppercase(),
                namingPolicy.tableUppercase(),
                tableNameResolver,
                namingPolicy.openQuote(),
                namingPolicy.closeQuote()
        ));
    }

    public JoyaSettings withQuoteStyle(QuoteStyle quoteStyle) {
        QuoteStyle qs = Objects.requireNonNullElse(quoteStyle, QuoteStyle.NONE);
        return withNamingPolicy(namingPolicy.withQuotes(qs.openQuote(), qs.closeQuote()));
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
        private TableNameResolver tableNameResolver = TableNameResolver.IDENTITY;
        private QuoteStyle quoteStyle = QuoteStyle.NONE;

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

        /** 动态表名解析策略（按月分表 / schema 切换 / 租户表名）。 */
        public Builder tableNameResolver(TableNameResolver tableNameResolver) {
            this.tableNameResolver = Objects.requireNonNullElse(tableNameResolver, TableNameResolver.IDENTITY);
            return this;
        }

        /** 标识符转义风格（反引号 / 双引号 / 方括号）。 */
        public Builder quoteStyle(QuoteStyle quoteStyle) {
            this.quoteStyle = Objects.requireNonNullElse(quoteStyle, QuoteStyle.NONE);
            return this;
        }

        public JoyaSettings build() {
            return new JoyaSettings(
                    new ExecutionOptions(queryTimeoutSeconds, batchSize),
                    new NamingPolicy(
                            maxJoinTable,
                            columnUppercase,
                            tableUppercase,
                            tableNameResolver,
                            quoteStyle.openQuote(),
                            quoteStyle.closeQuote())
            );
        }
    }
}
