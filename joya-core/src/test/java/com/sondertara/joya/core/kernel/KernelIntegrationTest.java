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
        recording.captured.clear();

        PageSlice<Map<String, Object>> result =
                template.queryPage(SqlStatement.select("SELECT id, name FROM t_user WHERE id <= 2"),
                        new MapRowMapper(), 0L, 10L);

        assertEquals(2, recording.captured.size(), "count 与 data 语句各观测一次");
        assertTrue(recording.captured.get(0).toUpperCase().contains("COUNT"), recording.captured.get(0));
        assertEquals("SELECT id, name FROM t_user WHERE id <= 2", recording.captured.get(1));

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
                events);

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
