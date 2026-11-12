package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.Executors;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.Operation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kernel 层端到端测试（H2 内存库）：观测、批量、执行器接缝与方言。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KernelIntegrationTest {

    /** 记录实际执行的 SQL，替代已删除的文本拦截器。 */
    private static final class RecordingListener implements SqlExecutionListener {
        final List<String> captured = new ArrayList<>();

        @Override
        public void beforeExecute(SqlExecutionContext ctx) {
            captured.add(ctx.statement().sql());
        }
    }

    private JdbcDataSource dataSource;
    private ExecutionKernel template;
    private RecordingListener recording;

    @BeforeAll
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kernel_it;DB_CLOSE_DELAY=-1");
        recording = new RecordingListener();
        template = templateWithListener(dataSource, recording);

        template.update(SqlStatement.update("DROP TABLE IF EXISTS t_user"));
        template.update(SqlStatement.update("CREATE TABLE t_user (id INT PRIMARY KEY, name VARCHAR(50))"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 1, "a"));
        template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 2, "b"));
    }

    private static ExecutionKernel templateWithListener(JdbcDataSource ds, SqlExecutionListener listener) {
        return new ExecutionKernel(new ExecutionKernel.Config(
                List.of(),
                List.of(listener),
                new DataSourceConnectionProvider(ds, 0),
                com.sondertara.joya.core.kernel.dialect.DialectStrategy.cached(ds), null, null, null, null, null));
    }

    @Test
    void queryPage_observesCountAndDataStatements() {
        List<SqlExecutionContext> contexts = new ArrayList<>();
        ExecutionKernel tpl = templateWithListener(dataSource, new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                contexts.add(ctx);
            }
        });

        PageSlice<Map<String, Object>> result =
                tpl.queryPage(SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                        new MapRowMapper(), 0L, 10L);

        assertEquals(2, contexts.size(), "count 与 data 语句各观测一次");
        SqlExecutionContext countCtx = contexts.get(0);
        SqlExecutionContext dataCtx = contexts.get(1);

        assertTrue(countCtx.statement().sql().toUpperCase().contains("COUNT"), countCtx.statement().sql());
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", countCtx.logicalStatement().sql());
        assertTrue(countCtx.inPage());
        assertEquals(0L, countCtx.offset());
        assertEquals(10L, countCtx.limit());

        assertEquals("SELECT id, name FROM t_user WHERE id <= 2 LIMIT ?", dataCtx.statement().sql());
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", dataCtx.logicalStatement().sql());
        assertTrue(dataCtx.inPage());
        assertEquals(0L, dataCtx.offset());
        assertEquals(10L, dataCtx.limit());
        assertSame(countCtx.logicalStatement(), dataCtx.logicalStatement(), "隐式计数分页共享同一份改写后逻辑语句");

        assertNotNull(result);
        assertEquals(2L, result.total());
        assertEquals(2, result.rows().size());
    }

    @Test
    void update_isObserved() {
        recording.captured.clear();
        int rows = template.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 5, "e"));

        assertEquals(1, rows);
        assertEquals(1, recording.captured.size());
    }

    @Test
    void batchUpdate_multiColumnMultiRow() {
        recording.captured.clear();
        List<Object[]> batch = List.of(
                new Object[]{10, "batch-a"},
                new Object[]{11, "batch-b"},
                new Object[]{12, "batch-c"});

        SqlStatement batchTpl = SqlStatement.of("INSERT INTO t_user VALUES (?, ?)",
                List.of(), StatementMetadata.of(Operation.INSERT));
        int[] rows = template.batchExecute(BatchSqlStatement.fromTemplate(batchTpl, batch, 1000)).batchCounts();

        assertEquals(1, recording.captured.size(), "整批只观测一次");
        assertEquals("INSERT INTO t_user VALUES (?, ?)", recording.captured.get(0));
        assertEquals(batch.size(), rows.length);
        for (int r : rows) {
            assertEquals(1, r);
        }
        Long count = template.queryOne(
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id BETWEEN 10 AND 12"),
                new SingleColumnRowMapper<>(Long.class));
        assertEquals(3L, count);
    }

    @Test
    void batchListener_seesBatchFlagRowCountAndDbType() {
        List<Object> seen = new ArrayList<>();
        ExecutionKernel tpl = templateWithListener(dataSource, new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                seen.add(ctx.batch());
                seen.add(ctx.attributes().get(SqlExecutionContext.ATTR_BATCH_ROWS));
                seen.add(ctx.statement().metadata().dbType());
                seen.add(ctx.statement().parameters().size());
            }
        });
        tpl.batchExecute(BatchSqlStatement.fromTemplate(
                SqlStatement.of("INSERT INTO t_user VALUES (?, ?)", List.of(), StatementMetadata.of(Operation.INSERT)),
                List.<Object[]>of(new Object[]{9020, "x"}, new Object[]{9021, "y"}), 1000));

        assertEquals(true, seen.get(0));
        assertEquals(2, seen.get(1));
        assertNotNull(seen.get(2));
        assertEquals(2, seen.get(3), "批量探针参数必须与占位符一一对应");
    }

    @Test
    void explicitCountQueryPage_observesBothCountAndDataStatements() {
        List<SqlExecutionContext> contexts = new ArrayList<>();
        ExecutionKernel tpl = templateWithListener(dataSource, new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                contexts.add(ctx);
            }
        });

        PageSlice<Map<String, Object>> page = tpl.queryPage(
                SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id <= 2"),
                new MapRowMapper(), 0L, 10L);

        assertEquals(2L, page.total());
        assertEquals(2, page.rows().size());
        assertEquals(2, contexts.size());
        assertEquals("SELECT count(*) FROM t_user WHERE id <= 2", contexts.get(0).statement().sql());
        assertEquals("SELECT count(*) FROM t_user WHERE id <= 2", contexts.get(0).logicalStatement().sql());
        assertTrue(contexts.get(0).inPage());
        assertEquals(0L, contexts.get(0).offset());
        assertEquals(10L, contexts.get(0).limit());

        assertEquals("SELECT id, name FROM t_user WHERE id <= 2 LIMIT ?", contexts.get(1).statement().sql());
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", contexts.get(1).logicalStatement().sql());
        assertTrue(contexts.get(1).inPage());
        assertEquals(0L, contexts.get(1).offset());
        assertEquals(10L, contexts.get(1).limit());

        contexts.clear();
        PageSlice<Map<String, Object>> empty = tpl.queryPage(
                SqlStatement.select("SELECT id, name FROM t_user WHERE id < 0"),
                SqlStatement.select("SELECT count(*) FROM t_user WHERE id < 0"),
                new MapRowMapper(), 0L, 10L);
        assertEquals(0L, empty.total());
        assertEquals(1, contexts.size(), "无数据时不执行、也不观测数据语句");
    }

    @Test
    void paginationObservability_deepOffsetDialectsIdentityStreamCloseAndUnsupportedFailFast() {
        // 1. 深分页（offset = 1_000_000）+ 执行器身份断言（ctx.statement() 与 Executor 实际收到的实例严格同一引用）
        List<SqlExecutionContext> beforeContexts = new ArrayList<>();
        List<SqlExecutionContext> closedContexts = new ArrayList<>();
        List<SqlStatement> executedStatements = new ArrayList<>();
        Executor.Factory recordingFactory = (connections, translator) -> {
            Executor delegate = Executors.JDBC.create(connections, translator);
            return (Executor) java.lang.reflect.Proxy.newProxyInstance(
                    Executor.class.getClassLoader(),
                    new Class<?>[]{Executor.class},
                    (proxy, method, args) -> {
                        if (args != null && args.length > 0 && args[0] instanceof SqlStatement stmt) {
                            executedStatements.add(stmt);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        SqlExecutionListener listener = new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                beforeContexts.add(ctx);
            }

            @Override
            public void onStreamClosed(SqlExecutionContext ctx) {
                closedContexts.add(ctx);
            }
        };

        ExecutionKernel h2Kernel = new ExecutionKernel(new ExecutionKernel.Config(
                List.of(),
                List.of(listener),
                new DataSourceConnectionProvider(dataSource, 0),
                DialectStrategy.cached(dataSource),
                null, null, null, null, recordingFactory));

        // 1a. H2 (LIMIT_OFFSET) 深分页 queryLimit
        h2Kernel.queryLimit(SqlStatement.select("SELECT id, name FROM t_user ORDER BY id"),
                new MapRowMapper(), 1_000_000L, 20L);
        assertEquals(1, beforeContexts.size());
        SqlExecutionContext deepH2 = beforeContexts.get(0);
        assertEquals("SELECT id, name FROM t_user ORDER BY id LIMIT ? OFFSET ?", deepH2.statement().sql());
        assertEquals("SELECT id, name FROM t_user ORDER BY id", deepH2.logicalStatement().sql());
        assertTrue(deepH2.inPage());
        assertEquals(1_000_000L, deepH2.offset());
        assertEquals(20L, deepH2.limit());
        assertEquals(List.of(20L, 1_000_000L), deepH2.statement().args());
        assertSame(deepH2.statement(), executedStatements.get(0), "所执行即所观测：ctx.statement() 必须与送入 Executor 的实例同一引用");

        // 1b. 流式分页 onStreamClosed 持有同一 ctx（含 PaginationView）且实例一致
        beforeContexts.clear();
        executedStatements.clear();
        try (var stream = h2Kernel.queryStream(
                SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2 ORDER BY id"),
                new MapRowMapper(), 1L, 5L)) {
            assertEquals(1, stream.toList().size());
        }
        assertEquals(1, beforeContexts.size());
        assertEquals(1, closedContexts.size());
        assertSame(beforeContexts.get(0), closedContexts.get(0));
        assertTrue(closedContexts.get(0).inPage());
        assertEquals(1L, closedContexts.get(0).offset());
        assertEquals(5L, closedContexts.get(0).limit());
        assertSame(beforeContexts.get(0).statement(), executedStatements.get(0));

        // 1c. 非分页执行：inPage() == false，logicalStatement() 与 statement() 同一引用；3 参构造保持兼容
        beforeContexts.clear();
        executedStatements.clear();
        h2Kernel.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = ?", 1), new MapRowMapper());
        SqlExecutionContext nonPage = beforeContexts.get(0);
        assertFalse(nonPage.inPage());
        assertEquals(-1L, nonPage.offset());
        assertEquals(-1L, nonPage.limit());
        assertSame(nonPage.statement(), nonPage.logicalStatement());
        assertSame(nonPage.statement(), executedStatements.get(0));
        SqlExecutionContext legacyCtor = new SqlExecutionContext(nonPage.statement(), false, Map.of());
        assertFalse(legacyCtor.inPage());
        assertSame(nonPage.statement(), legacyCtor.logicalStatement());

        // 2. SQL Server (ANSI_OFFSET_FETCH) 深分页双视图（通过零 JDBC 执行桩验证 SQL 与参数）
        beforeContexts.clear();
        executedStatements.clear();
        Executor.Factory noOpSelectFactory = (connections, translator) -> (Executor) java.lang.reflect.Proxy.newProxyInstance(
                Executor.class.getClassLoader(),
                new Class<?>[]{Executor.class},
                (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof SqlStatement stmt) {
                        executedStatements.add(stmt);
                    }
                    return List.of();
                });
        ExecutionKernel sqlServerKernel = new ExecutionKernel(new ExecutionKernel.Config(
                List.of(),
                List.of(listener),
                new DataSourceConnectionProvider(dataSource, 0),
                DialectStrategy.fixed(Dialect.SQLSERVER),
                null, null, null, null, noOpSelectFactory));
        sqlServerKernel.queryLimit(SqlStatement.select("SELECT id, name FROM t_user ORDER BY id"),
                new MapRowMapper(), 1_000_000L, 20L);
        SqlExecutionContext deepMsSql = beforeContexts.get(0);
        assertEquals("SELECT id, name FROM t_user ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                deepMsSql.statement().sql());
        assertEquals("SELECT id, name FROM t_user ORDER BY id", deepMsSql.logicalStatement().sql());
        assertEquals(1_000_000L, deepMsSql.offset());
        assertEquals(20L, deepMsSql.limit());
        assertEquals(List.of(1_000_000L, 20L), deepMsSql.statement().args());
        assertSame(deepMsSql.statement(), executedStatements.get(0));

        // 3. UNSUPPORTED 方言（如 Hive）在进入观测前 Fail-Fast，不触发 beforeExecute 或 onError
        List<String> errorEvents = new ArrayList<>();
        ExecutionKernel hiveKernel = new ExecutionKernel(new ExecutionKernel.Config(
                List.of(),
                List.of(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        errorEvents.add("before");
                    }

                    @Override
                    public void onError(SqlExecutionContext ctx, Throwable error) {
                        errorEvents.add("onError");
                    }
                }),
                new DataSourceConnectionProvider(dataSource, 0),
                DialectStrategy.fixed(Dialect.HIVE),
                null, null, null, null, noOpSelectFactory));
        assertThrows(UnsupportedOperationException.class,
                () -> hiveKernel.queryLimit(SqlStatement.select("SELECT id FROM t_user"), new MapRowMapper(), 0L, 10L));
        assertEquals(List.of(), errorEvents, "不支持分页的方言应在进入观测器前直接 Fail-Fast");
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
        ExecutionKernel tpl = new ExecutionKernel(new ExecutionKernel.Config(
                List.of(), List.of(),
                base, com.sondertara.joya.core.kernel.dialect.DialectStrategy.cached(dataSource), null, null, null, null, factory));

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
        assertTrue(first == second);
        assertTrue(first instanceof Dialect.Standard);
        assertNotNull(first.dbType());
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
        assertNotSame(page.sql(), stmt.sql());
    }

    @Test
    void templateStatement_carriesDetectedDbType() {
        List<Operation> ops = new ArrayList<>();
        ExecutionKernel probeTemplate = templateWithListener(dataSource, new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                ops.add(ctx.statement().metadata().operation());
            }
        });
        probeTemplate.update(SqlStatement.update("INSERT INTO t_user VALUES (?, ?)", 20, "p"));

        assertEquals(List.of(Operation.UPDATE), ops);
    }

    @Test
    void parameterCountMismatch_failsFastBeforeJdbc() {
        assertThrows(IllegalArgumentException.class,
                () -> template.queryList(SqlStatement.select("SELECT id FROM t_user WHERE id = ?", 1, 2),
                        new MapRowMapper()));
    }
}
