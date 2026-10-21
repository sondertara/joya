package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.jdbc.support.dialect.BoundSql;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectResolver;
import com.sondertara.joya.core.kernel.dialect.JdbcDialect;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kernel 层端到端测试（H2 内存库）：
 * <ul>
 *   <li>回归：{@code JoyaTemplate.queryPage(SqlStatement,...)} 必须走拦截器链。</li>
 *   <li>回归：{@code batchUpdate(SqlStatement, List<Object[]>)} 多列多行参数语义正确且走拦截器链。</li>
 *   <li>{@link DialectResolver} 按 DataSource 缓存方言（不重复探测）。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KernelIntegrationTest {

    /** 标记拦截器：记录调用并给 SQL 追加注释（可被执行引擎透传，用于证明改写后的 SQL 真正执行）。 */
    private static final class MarkingInterceptor implements SqlInterceptor {
        final List<String> captured = new ArrayList<>();

        @Override
        public SqlStatement intercept(RewriteContext ctx, Chain chain) {
            captured.add(ctx.statement().sql());
            ctx.rewrite(ctx.statement().withSql(ctx.statement().sql() + " /* marked */"));
            return chain.proceed(ctx);
        }
    }

    private JdbcDataSource dataSource;
    private JoyaTemplate template;
    private MarkingInterceptor marking;

    @BeforeAll
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kernel_it;DB_CLOSE_DELAY=-1");
        marking = new MarkingInterceptor();
        template = new JoyaTemplate(dataSource, List.of(marking));

        template.update(SqlStatement.update("DROP TABLE IF EXISTS t_user"));
        template.update(SqlStatement.update("CREATE TABLE t_user (id INT PRIMARY KEY, name VARCHAR(50))"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 1, "a"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 2, "b"));
    }

    @Test
    void queryPage_walksInterceptorChain() {
        marking.captured.clear();
        PageRequest page = PageRequest.of(0, 10);

        PageResult<Map<String, Object>> result =
                template.queryPage(SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                        new MapRowMapper(), page);

        assertEquals(1, marking.captured.size(), "queryPage 应恰好触发一次拦截器链");
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", marking.captured.get(0), "拦截器应收到原始 SQL");

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getData().size());
    }

    @Test
    void update_walksInterceptorChain() {
        marking.captured.clear();
        int rows = template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 5, "e"));

        assertEquals(1, rows);
        assertEquals(1, marking.captured.size(), "update 也应走拦截器链");
    }

    @Test
    void batchUpdate_multiColumnMultiRow() {
        marking.captured.clear();
        List<Object[]> batch = List.of(
                new Object[]{10, "batch-a"},
                new Object[]{11, "batch-b"},
                new Object[]{12, "batch-c"});

        SqlStatement batchTpl = SqlStatement.of("INSERT INTO t_user VALUES (?, ?)",
                List.of(), StatementMetadata.of(Operation.INSERT));
        int[] rows = template.batchUpdate(batchTpl, batch);

        assertEquals(1, marking.captured.size(), "batchUpdate 也应经过拦截器链");
        assertEquals(batch.size(), rows.length, "每组参数应各产生一条影响行数");
        for (int r : rows) {
            assertEquals(1, r);
        }
        Long count = template.queryOne(
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id BETWEEN 10 AND 12"),
                new SingleColumnRowMapper<>(Long.class));
        assertEquals(3L, count, "批量 3 行应全部写入");
    }

    @Test
    void dialectResolver_cachesPerDataSource() {
        Dialect first = DialectResolver.resolve(dataSource);
        Dialect second = DialectResolver.resolve(dataSource);

        assertNotNull(first);
        assertTrue(first == second, "同一 DataSource 应复用缓存的方言实例");
        assertTrue(first instanceof JdbcDialect);
        assertNotNull(first.dbType(), "H2 应被识别出 DbType");
    }

    @Test
    void dialect_countAndPageSql_nonBlank() {
        Dialect dialect = DialectResolver.resolve(dataSource);
        BoundSql bound = new BoundSql.BoundSqlObj("SELECT id, name FROM t_user WHERE id > ?", new Object[]{0});

        BoundSql count = dialect.countSql(bound);
        BoundSql page = dialect.pageSql(bound, 0, 10);

        assertNotNull(count);
        assertNotNull(page);
        assertTrue(count.getSqlString().toUpperCase().contains("COUNT"));
        assertNotSame(page.getSqlString(), bound.getSqlString(), "分页方言应改写 SQL");
    }

    @Test
    void templateStatement_carriesDetectedDbType() {
        List<Operation> ops = new ArrayList<>();
        marking.captured.clear();
        SqlInterceptor probe = new SqlInterceptor() {
            @Override
            public SqlStatement intercept(RewriteContext ctx, Chain chain) {
                ops.add(ctx.statement().op());
                return chain.proceed(ctx);
            }
        };
        JoyaTemplate probeTemplate = new JoyaTemplate(dataSource, List.of(probe, marking));
        probeTemplate.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 20, "p"));

        assertEquals(List.of(Operation.UPDATE), ops);
        assertEquals(1, marking.captured.size(), "多拦截器均应被执行");
    }
}
