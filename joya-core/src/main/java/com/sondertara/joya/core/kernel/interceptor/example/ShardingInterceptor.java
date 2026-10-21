package com.sondertara.joya.core.kernel.interceptor.example;

import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.TableRef;

import java.util.function.Function;

/**
 * 分库分表拦截器示例 —— 演示如何优雅地改写表名。
 * <p>
 * 通过 {@link SqlStatement#withTable(String, String)} 改写，无需字符串 {@code replace}，
 * 避免 {@code FROM t_user} 与 {@code t_user_log} 误替换的错位问题。
 *
 * <p>使用：{@code new JoyaTemplate(ds, List.of(new ShardingInterceptor(shardRule)))}
 */
public final class ShardingInterceptor implements SqlInterceptor {

    private final Function<String, String> shardRule;

    public ShardingInterceptor(Function<String, String> shardRule) {
        this.shardRule = shardRule;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(RewriteContext ctx) {
        return !ctx.statement().tables().isEmpty();
    }

    @Override
    public SqlStatement intercept(RewriteContext ctx, Chain chain) {
        SqlStatement statement = ctx.statement();
        for (TableRef table : statement.tables()) {
            String target = shardRule.apply(table.name());
            if (target != null && !target.equals(table.name())) {
                statement = statement.withTable(table.name(), target);
            }
        }
        ctx.rewrite(statement);
        return chain.proceed(ctx);
    }
}
