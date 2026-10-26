package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.DefaultChain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 拦截器链语义测试：order 升序确定性执行、supports 过滤、上下文 attrs 透传。
 */
class InterceptorChainTest {

    /** 记录执行的拦截器，并向 SQL 追加标记。 */
    private static final class TaggingInterceptor implements SqlInterceptor {
        private final String tag;
        private final int order;
        private final boolean supports;
        final List<String> executed;

        TaggingInterceptor(String tag, int order, boolean supports, List<String> executed) {
            this.tag = tag;
            this.order = order;
            this.supports = supports;
            this.executed = executed;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean supports(RewriteContext ctx) {
            return supports;
        }

        @Override
        public SqlStatement intercept(RewriteContext ctx, Chain chain) {
            executed.add(tag);
            ctx.rewrite(ctx.statement().withSql(ctx.statement().sql() + " " + tag));
            return chain.proceed(ctx);
        }
    }

    @Test
    void chain_executesInRegistrationOrder() {
        List<String> executed = new ArrayList<>();
        // DefaultChain 本身不排序：排序职责在 JoyaTemplate 构造时完成（见 templateSortsInterceptorsByOrder）
        List<SqlInterceptor> interceptors = List.of(
                new TaggingInterceptor("first", 10, true, executed),
                new TaggingInterceptor("second", 5, true, executed));

        SqlStatement result = new DefaultChain(interceptors)
                .proceed(new RewriteContext(SqlStatement.select("SELECT 1"), Map.of()));

        assertEquals(List.of("first", "second"), executed, "链应按注册顺序执行");
        assertEquals("SELECT 1 first second", result.sql(), "改写应按执行顺序叠加");
    }

    @Test
    void templateSortsInterceptorsByOrder() {
        List<String> executed = new ArrayList<>();
        // 故意乱序注册，验证 JoyaTemplate 构造时按 order 升序排列
        List<SqlInterceptor> unordered = new ArrayList<>();
        unordered.add(new TaggingInterceptor("later", 10, true, executed));
        unordered.add(new TaggingInterceptor("earlier", 5, true, executed));
        java.util.Collections.shuffle(unordered);

        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:chain_sort;DB_CLOSE_DELAY=-1");
        com.sondertara.joya.core.kernel.JoyaTemplate template =
                new com.sondertara.joya.core.kernel.JoyaTemplate(ds, unordered);

        List<String> orders = template.interceptors().stream()
                .map(i -> ((TaggingInterceptor) i).tag)
                .toList();
        assertEquals(List.of("earlier", "later"), orders, "模板应按 order 升序排列拦截器");
    }

    @Test
    void chain_skipsUnsupportedInterceptor() {
        List<String> executed = new ArrayList<>();
        List<SqlInterceptor> interceptors = List.of(
                new TaggingInterceptor("skip", 1, false, executed),
                new TaggingInterceptor("run", 2, true, executed));

        SqlStatement result = new DefaultChain(interceptors)
                .proceed(new RewriteContext(SqlStatement.select("SELECT 1"), Map.of()));

        assertEquals(List.of("run"), executed, "supports=false 的拦截器应被跳过");
        assertEquals("SELECT 1 run", result.sql());
    }

    @Test
    void chain_emptyReturnsOriginalStatement() {
        SqlStatement original = SqlStatement.select("SELECT 1", 42);
        SqlStatement result = new DefaultChain(List.of())
                .proceed(new RewriteContext(original, Map.of()));
        assertEquals(original, result);
    }

    @Test
    void context_originalIsImmutableSnapshot_andAttrsPassedThrough() {
        SqlStatement original = SqlStatement.select("SELECT * FROM t_user", 1);
        RewriteContext ctx = new RewriteContext(original, Map.of("tenantId", 7L));

        ctx.rewrite(original.withSql("SELECT * FROM t_user_7"));

        assertEquals(original, ctx.original(), "original 应保持为改写前快照");
        assertEquals("SELECT * FROM t_user_7", ctx.statement().sql());
        assertEquals(7L, ctx.<Long>attr("tenantId"), "请求级 attrs 应透传给拦截器");
    }

    @Test
    void chain_attrsVisibleToDownstreamInterceptor() {
        List<String> seen = new ArrayList<>();
        SqlInterceptor reader = new SqlInterceptor() {
            @Override
            public int order() {
                return 2;
            }

            @Override
            public SqlStatement intercept(RewriteContext ctx, Chain chain) {
                seen.add(String.valueOf(ctx.<Long>attr("shardKey")));
                return chain.proceed(ctx);
            }
        };
        SqlInterceptor tail = new SqlInterceptor() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public SqlStatement intercept(RewriteContext ctx, Chain chain) {
                ctx.rewrite(ctx.statement().appendParameter("shardKey", ctx.<Long>attr("shardKey"),
                        com.sondertara.joya.core.kernel.statement.ParameterSource.RAW));
                return chain.proceed(ctx);
            }
        };

        SqlStatement result = new DefaultChain(List.of(reader, tail))
                .proceed(new RewriteContext(SqlStatement.select("SELECT * FROM t WHERE k = ?"), Map.of("shardKey", 99L)));

        assertEquals(List.of("99"), seen, "下游拦截器应能读到上游写入的 attrs");
        assertEquals(List.of(99L), result.args());
        assertEquals(Operation.SELECT, result.metadata().operation());
    }
}
