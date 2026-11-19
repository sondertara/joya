package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.naming.TableNameResolver;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
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

    public static JoyaSettings of(ExecutionOptions executionOptions, NamingPolicy namingPolicy) {
        return new JoyaSettings(executionOptions, namingPolicy);
    }

    public JoyaSettings withExecutionOptions(ExecutionOptions newExecutionOptions) {
        return new JoyaSettings(newExecutionOptions, namingPolicy);
    }

    public JoyaSettings withQueryTimeoutSeconds(int queryTimeoutSeconds) {
        return withExecutionOptions(executionOptions.withQueryTimeoutSeconds(queryTimeoutSeconds));
    }

    /**
     * 设置默认查询超时（{@code null} 或 {@link Duration#ZERO} 表示不限制；正值按秒向上取整，至少 1 秒；负值抛异常）。
     */
    public JoyaSettings withQueryTimeout(@Nullable Duration queryTimeout) {
        return withQueryTimeoutSeconds(toTimeoutSeconds(queryTimeout));
    }

    public JoyaSettings withBatchSize(int batchSize) {
        return withExecutionOptions(executionOptions.withBatchSize(batchSize));
    }

    public JoyaSettings withNamingPolicy(NamingPolicy newNamingPolicy) {
        return new JoyaSettings(executionOptions, newNamingPolicy);
    }

    public JoyaSettings withTableNameResolver(TableNameResolver tableNameResolver) {
        return withNamingPolicy(namingPolicy.withTableNameResolver(tableNameResolver));
    }

    public JoyaSettings withQuoteStyle(QuoteStyle quoteStyle) {
        QuoteStyle qs = Objects.requireNonNullElse(quoteStyle, QuoteStyle.NONE);
        return withNamingPolicy(namingPolicy.withQuotes(qs.openQuote(), qs.closeQuote()));
    }

    public Builder toBuilder() {
        return new Builder(this);
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
        private char openQuote = NamingPolicy.DEFAULT.openQuote();
        private char closeQuote = NamingPolicy.DEFAULT.closeQuote();

        private Builder() {
        }

        private Builder(JoyaSettings settings) {
            Objects.requireNonNull(settings, "settings must not be null");
            this.queryTimeoutSeconds = settings.executionOptions().queryTimeoutSeconds();
            this.batchSize = settings.executionOptions().batchSize();
            this.maxJoinTable = settings.namingPolicy().maxJoinTable();
            this.columnUppercase = settings.namingPolicy().columnUppercase();
            this.tableUppercase = settings.namingPolicy().tableUppercase();
            this.tableNameResolver = settings.namingPolicy().tableNameResolver();
            this.openQuote = settings.namingPolicy().openQuote();
            this.closeQuote = settings.namingPolicy().closeQuote();
        }

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        /**
         * 设置默认查询超时（{@code null} 或 {@link Duration#ZERO} 表示不限制；正值按秒向上取整，至少 1 秒；负值抛异常）。
         */
        public Builder queryTimeout(@Nullable Duration queryTimeout) {
            this.queryTimeoutSeconds = toTimeoutSeconds(queryTimeout);
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
            QuoteStyle effective = Objects.requireNonNullElse(quoteStyle, QuoteStyle.NONE);
            this.openQuote = effective.openQuote();
            this.closeQuote = effective.closeQuote();
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
                            openQuote,
                            closeQuote)
            );
        }
    }

    private static int toTimeoutSeconds(@Nullable Duration timeout) {
        if (timeout == null || timeout.isZero()) {
            return 0;
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("queryTimeout must not be negative: " + timeout);
        }
        long seconds = timeout.getSeconds();
        if (timeout.getNano() > 0 && seconds < Long.MAX_VALUE) {
            seconds++;
        }
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, seconds);
    }
}
