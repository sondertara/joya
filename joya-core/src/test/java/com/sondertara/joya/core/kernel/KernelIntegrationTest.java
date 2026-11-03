package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.Executors;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kernel 层端到端测试（H2 内存库）：
 * <ul>
 *   <li>回归：{@code ExecutionKernel.queryPage(SqlStatement,...)} 必须走拦截器链。</li>
 *   <li>回归：{@code batchExecute(BatchSqlStatement)} 多列多行参数语义正确，整批只触发一次拦截链，且拦截器只能改写文本。</li>
 *   <li>{@link Dialect} 按 {@link DialectStrategy} 缓存方言（不重复探测）。</li>
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
    private ExecutionKernel template;
    private MarkingInterceptor marking;

    @BeforeAll
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kernel_it;DB_CLOSE_DELAY=-1");
        marking = new MarkingInterceptor();
        template = templateWithInterceptors(dataSource, List.of(marking));

        template.update(SqlStatement.update("DROP TABLE IF EXISTS t_user"));
        template.update(SqlStatement.update("CREATE TABLE t_user (id INT PRIMARY KEY, name VARCHAR(50))"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 1, "a"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 2, "b"));
    }

    private static ExecutionKernel templateWithInterceptors(JdbcDataSource ds, List<SqlInterceptor> interceptors) {
        return new ExecutionKernel(new ExecutionKernel.Config(ds, interceptors, List.of(), List.of(),
                new DataSourceConnectionProvider(ds, 0), null, null, null, null, null, null));
    }

    @Test
    void queryPage_walksInterceptorChain() {
        marking.captured.clear();

        PageSlice<Map<String, Object>> result =
                template.queryPage(SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                        new MapRowMapper(), 0L, 10L);

        assertEquals(1, marking.captured.size(), "queryPage 应恰好触发一次拦截器链");
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", marking.captured.get(0), "拦截器应收到原始 SQL");

        assertNotNull(result);
        assertEquals(2L, result.total());
        assertEquals(2, result.rows().size());
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
        int[] rows = template.batchExecute(BatchSqlStatement.fromTemplate(batchTpl, batch, 1000)).batchCounts();

        assertEquals(1, marking.captured.size(), "批量也走文本拦截链，且整批只触发一次（模板探针）");
        assertEquals("INSERT INTO t_user VALUES (?, ?)", marking.captured.get(0), "拦截器应收到未改写的 SQL 模板");
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
    void batch_interceptorSeesBatchFlagRowCountAndDbType() {
        List<Object> seen = new ArrayList<>();
        ExecutionKernel tpl = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            seen.add(ctx.isBatch());
            seen.add(ctx.attr(RewriteContext.ATTR_BATCH_ROWS));
            seen.add(ctx.statement().metadata().dbType());
            seen.add(ctx.statement().parameters().size());
            return chain.proceed(ctx);
        }));
        tpl.batchExecute(BatchSqlStatement.fromTemplate(
                SqlStatement.of("INSERT INTO t_user VALUES (?, ?)", List.of(), StatementMetadata.of(Operation.INSERT)),
                List.<Object[]>of(new Object[]{9020, "x"}, new Object[]{9021, "y"}), 1000));

        assertEquals(true, seen.get(0), "批量模式标记");
        assertEquals(2, seen.get(1), "批量行数");
        assertNotNull(seen.get(2), "批量语句也应注入已探测的 dbType");
        assertEquals(0, seen.get(3), "探针参数为空");
    }

    @Test
    void batch_interceptorRewritesTextOnly_andAppendedParametersAreRejected() {
        ExecutionKernel hinting = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            ctx.rewrite(ctx.statement().withSql(ctx.statement().sql() + " /* hint */"));
            return chain.proceed(ctx);
        }));
        int[] ok = hinting.batchExecute(BatchSqlStatement.fromTemplate(
                SqlStatement.of("INSERT INTO t_user VALUES (?, ?)", List.of(), StatementMetadata.of(Operation.INSERT)),
                List.<Object[]>of(new Object[]{9030, "h"}), 1000)).batchCounts();
        assertEquals(1, ok.length);

        ExecutionKernel appending = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            ctx.rewrite(ctx.statement().appendParameter("tenant", 1, ParameterSource.RAW));
            return chain.proceed(ctx);
        }));
        assertThrows(UnsupportedOperationException.class, () -> appending.batchExecute(BatchSqlStatement.fromTemplate(
                SqlStatement.of("INSERT INTO t_user VALUES (?, ?)", List.of(), StatementMetadata.of(Operation.INSERT)),
                List.<Object[]>of(new Object[]{9031, "a"}), 1000)));

        ExecutionKernel extraPlaceholder = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            ctx.rewrite(ctx.statement().withSql("INSERT INTO t_user VALUES (?, ?, ?)"));
            return chain.proceed(ctx);
        }));
        assertThrows(UnsupportedOperationException.class, () -> extraPlaceholder.batchExecute(BatchSqlStatement.fromTemplate(
                SqlStatement.of("INSERT INTO t_user VALUES (?, ?)", List.of(), StatementMetadata.of(Operation.INSERT)),
                List.<Object[]>of(new Object[]{9032, "b"}), 1000)));
        Long rejected = template.queryOne(
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id IN (9031, 9032)"),
                new SingleColumnRowMapper<>(Long.class));
        assertEquals(0L, rejected, "被拒绝的批量不得有任何写入");
    }

    private static ExecutionKernel templateWithListener(JdbcDataSource ds, SqlExecutionListener listener) {
        return new ExecutionKernel(new ExecutionKernel.Config(ds, List.of(), List.of(), List.of(listener),
                new DataSourceConnectionProvider(ds, 0), null, null, null, null, null, null));
    }

    @Test
    void explicitCountQueryPage_observesBothCountAndDataStatements() {
        List<String> events = new ArrayList<>();
        ExecutionKernel tpl = templateWithListener(dataSource, new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                events.add(ctx.statement().sql());
            }
        });

        PageSlice<Map<String, Object>> page = tpl.queryPage(
                SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id <= 2"),
                new MapRowMapper(), 0L, 10L);

        assertEquals(2L, page.total());
        assertEquals(2, page.rows().size());
        assertEquals(List.of("SELECT count(*) FROM t_user WHERE id <= 2", "SELECT id, name FROM t_user WHERE id <= 2"),
                events, "计数语句与数据语句各被观测一次");

        events.clear();
        PageSlice<Map<String, Object>> empty = tpl.queryPage(
                SqlStatement.select("SELECT id, name FROM t_user WHERE id < 0"),
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id < 0"),
                new MapRowMapper(), 0L, 10L);
        assertEquals(0L, empty.total());
        assertEquals(1, events.size(), "无数据时不执行、也不观测数据语句");
    }

    @Test
    void executorIsAReplaceableSeam_andRebuiltForBoundTransactionalViews() {
        List<ConnectionProvider> created = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Executor.Factory factory = (connections, translator) -> {
            created.add(connections);
            Executor delegate = Executors.JDBC.create(connections, translator);
            return (Executor) java.lang.reflect.Proxy.newProxyInstance(
                    Executor.class.getClassLoader(), new Class<?>[]{Executor.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        DataSourceConnectionProvider base = new DataSourceConnectionProvider(dataSource, 0);
        ExecutionKernel tpl = new ExecutionKernel(new ExecutionKernel.Config(dataSource, List.of(), List.of(), List.of(),
                base, null, null, null, null, null, factory));

        tpl.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = 1"), new MapRowMapper());
        assertTrue(calls.contains("queryList"), "查询必须经过可替换的 Executor 端口: " + calls);
        assertEquals(1, created.size());
        assertSame(base, created.get(0));

        DataSourceConnectionProvider other = new DataSourceConnectionProvider(dataSource, 0);
        ExecutionKernel bound = tpl.bind(other);
        assertEquals(2, created.size(), "bind 派生视图时用新连接来源重建执行器");
        assertSame(other, created.get(1));
        calls.clear();
        bound.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = 1"), new MapRowMapper());
        assertTrue(calls.contains("queryList"));
    }

    @Test
    void dialectResolver_cachesPerDataSource() {
        Dialect first = template.dialect();
        Dialect second = template.dialect();

        assertNotNull(first);
        assertTrue(first == second, "同一 ExecutionKernel 应复用缓存的方言实例");
        assertTrue(first instanceof Dialect.Standard);
        assertNotNull(first.dbType(), "H2 应被识别出 DbType");
    }

    @Test
    void dialect_countAndPageSql_nonBlank() {
        Dialect dialect = template.dialect();
        SqlStatement stmt = SqlStatement.select("SELECT id, name FROM t_user WHERE id > ?", 0);

        SqlStatement count = dialect.countSql(stmt);
        SqlStatement page = dialect.pageSql(stmt, 0, 10);

        assertNotNull(count);
        assertNotNull(page);
        assertTrue(count.sql().toUpperCase().contains("COUNT"));
        assertNotSame(page.sql(), stmt.sql(), "分页方言应改写 SQL");
    }

    @Test
    void templateStatement_carriesDetectedDbType() {
        List<Operation> ops = new ArrayList<>();
        marking.captured.clear();
        SqlInterceptor probe = new SqlInterceptor() {
            @Override
            public SqlStatement intercept(RewriteContext ctx, Chain chain) {
                ops.add(ctx.statement().metadata().operation());
                return chain.proceed(ctx);
            }
        };
        ExecutionKernel probeTemplate = templateWithInterceptors(dataSource, List.of(probe, marking));
        probeTemplate.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 20, "p"));

        assertEquals(List.of(Operation.UPDATE), ops);
        assertEquals(1, marking.captured.size(), "多拦截器均应被执行");
    }

    @Test
    void singleStatement_interceptorMismatchedPlaceholderCount_failsFast() {
        ExecutionKernel extraParam = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            ctx.rewrite(ctx.statement().appendParameter("tenant", 1, ParameterSource.RAW));
            return chain.proceed(ctx);
        }));
        assertThrows(IllegalStateException.class,
                () -> extraParam.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = ?", 1),
                        new MapRowMapper()));

        ExecutionKernel extraPlaceholder = templateWithInterceptors(dataSource, List.of((ctx, chain) -> {
            ctx.rewrite(ctx.statement().withSql("SELECT id FROM t_user WHERE id = ? AND name = ?"));
            return chain.proceed(ctx);
        }));
        assertThrows(IllegalStateException.class,
                () -> extraPlaceholder.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = ?", 1),
                        new MapRowMapper()));
    }
}
