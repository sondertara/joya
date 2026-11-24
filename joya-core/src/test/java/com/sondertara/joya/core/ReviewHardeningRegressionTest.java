package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodeTransformer;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.support.LoggingOutputStream;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.dsl.Exprs;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.tx.TxSessionSupport;
import com.sondertara.joya.core.tx.TxTimeoutException;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.io.StringReader;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码评审并发竞态、资源释放、映射边界与 AST 2.0 单趟渲染专项回归测试。
 */
class ReviewHardeningRegressionTest {

    public enum Role {
        ADMIN, USER
    }

    @Entity
    @Table(name = "t_rh_item")
    public static class RhItem {
        @Id
        private Long id;
        @Column(name = "user_id")
        private Long userId;
        @Column(name = "title")
        private String title;
        @Column(name = "score")
        private int score;

        public RhItem() {
        }

        public RhItem(Long id, Long userId, String title, int score) {
            this.id = id;
            this.userId = userId;
            this.title = title;
            this.score = score;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getTitle() {
            return title;
        }

        public RhItem setTitle(String title) {
            this.title = title;
            return this;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }
    }

    public static class TurkishColumnBean {
        @Column(name = "USER_ID")
        private Long userId;
        private String title;
        private int score;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getTitle() {
            return title;
        }

        // 链式非 void setter：验证 MethodHandle.asType 预适配 invokeExact 兼容性
        public TurkishColumnBean setTitle(String title) {
            this.title = title;
            return this;
        }

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }
    }

    public record ItemSummaryRecord(Long id, String title, int score) {
    }

    public record StatefulGetter(boolean selectTitle) implements IGetter<RhItem, Object> {
        @Override
        public Object apply(RhItem source) {
            return selectTitle ? source.getTitle() : source.getId();
        }

        private Object writeReplace() {
            return new SerializedLambda(
                    ReviewHardeningRegressionTest.class,
                    "com/sondertara/joya/core/function/IGetter",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    MethodHandleInfo.REF_invokeVirtual,
                    "com/sondertara/joya/core/ReviewHardeningRegressionTest$RhItem",
                    selectTitle ? "getTitle" : "getId",
                    selectTitle ? "()Ljava/lang/String;" : "()Ljava/lang/Long;",
                    "(Lcom/sondertara/joya/core/ReviewHardeningRegressionTest$RhItem;)Ljava/lang/Object;",
                    new Object[0]);
        }
    }

    @Entity
    @Table(name = "T_UPPER_TENANT")
    public static class UpperTenantEntity {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "amount")
        private Integer amount;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:review_hardening;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        JoyaClient init = JoyaClient.create(dataSource);
        init.sql("DROP TABLE IF EXISTS t_rh_item").update();
        init.sql("CREATE TABLE t_rh_item (id BIGINT PRIMARY KEY, user_id BIGINT, title VARCHAR(64), score INT)").update();
        init.sql("INSERT INTO t_rh_item (id, user_id, title, score) VALUES (1, 100, 'alpha', 10), (2, 200, 'beta', NULL)").update();
    }

    @Test
    void observeStream_closesOpenedStreamAndConnectionWhenAfterExecuteThrowsError() {
        CountingConnectionProvider counting = new CountingConnectionProvider(dataSource);
        JoyaClient joya = JoyaClient.builder(dataSource)
                .connectionProvider(counting)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void afterExecute(SqlExecutionContext ctx, SqlExecutionResult result) {
                        throw new AssertionError("listener boom");
                    }
                })
                .build();

        AssertionError err = assertThrows(AssertionError.class, () -> joya.from(RhItem.class).stream());
        assertEquals("listener boom", err.getMessage());
        assertEquals(0, counting.activeConnections.get(), "Listener Error after stream open must release Connection");
    }

    @Test
    void txAutoClosedStream_triggersOnStreamClosedExactlyOnce() {
        AtomicInteger streamClosedEvents = new AtomicInteger();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void onStreamClosed(SqlExecutionContext ctx) {
                        streamClosedEvents.incrementAndGet();
                    }
                })
                .build();

        final Stream<RhItem>[] leakedRef = new Stream[1];
        joya.tx(tx -> {
            Stream<RhItem> stream = tx.from(RhItem.class).stream();
            leakedRef[0] = stream;
            assertTrue(stream.findFirst().isPresent());
            // 故意不在 try-with-resources 中关闭，验证事务提交收口时仍触发 onStreamClosed
        });

        assertEquals(1, streamClosedEvents.get(), "Tx commit auto-close must notify onStreamClosed");
        leakedRef[0].close();
        assertEquals(1, streamClosedEvents.get(), "Repeated close() must be idempotent");
    }

    @Test
    void nonTransactionalStream_eagerlyReleasesConnectionOnExhaustionAndError() {
        CountingConnectionProvider counting = new CountingConnectionProvider(dataSource);
        JoyaClient joya = JoyaClient.builder(dataSource)
                .connectionProvider(counting)
                .build();

        // 1. 未包裹 try-with-resources 但完整消费完流时，底层连接应立即归还
        List<RhItem> all = joya.from(RhItem.class).stream().toList();
        assertEquals(2, all.size());
        assertEquals(0, counting.activeConnections.get(), "Exhausted stream must eagerly release Connection");

        // 2. 流读取中途抛出异常时，底层连接同样立即释放
        assertThrows(IllegalStateException.class, () ->
                joya.sql("SELECT id FROM t_rh_item")
                        .stream(rs -> {
                            throw new IllegalStateException("mapper failure");
                        })
                        .toList());
        assertEquals(0, counting.activeConnections.get(), "Failed stream iteration must eagerly release Connection");
    }

    @Test
    void withConnection_forbidsTransactionControlOnBothClientAndTx() {
        JoyaClient joya = JoyaClient.create(dataSource);

        assertThrows(UnsupportedOperationException.class,
                () -> joya.withConnection(conn -> {
                    conn.setAutoCommit(false);
                    return null;
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> joya.withConnection(conn -> {
                    conn.commit();
                    return null;
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> joya.withConnection(conn -> {
                    conn.rollback();
                    return null;
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> joya.withConnection(conn -> {
                    conn.setReadOnly(true);
                    return null;
                }));
        assertThrows(UnsupportedOperationException.class,
                () -> joya.withConnection(conn -> {
                    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    return null;
                }));
    }

    @Test
    void txSessionSupport_handlesDeadlineExpiryWithoutOneSecondGraceWindowAndOverflow() throws Exception {
        TxSessionSupport<Object> support = new TxSessionSupport<>();
        assertFalse(support.isDeadlineExpired());
        assertEquals(OptionalInt.of(7), support.resolveQueryTimeoutSeconds(OptionalInt.of(7)));

        AtomicBoolean expiredMarked = new AtomicBoolean();
        support.startDeadline(Duration.ZERO);
        assertTrue(support.isDeadlineExpired());
        assertThrows(TxTimeoutException.class,
                () -> support.resolveQueryTimeoutSeconds(OptionalInt.empty(), () -> expiredMarked.set(true)));
        assertTrue(expiredMarked.get());

        // 超大 Duration 不得因 toNanos() 溢出抛 ArithmeticException
        support.startDeadline(Duration.ofSeconds(Long.MAX_VALUE));
        assertFalse(support.isDeadlineExpired());
        assertEquals(Integer.MAX_VALUE, TxSessionSupport.ceilSeconds(Duration.ofSeconds(Long.MAX_VALUE)));

        // 真实事务内超时后执行批量分片应立即抛 TxTimeoutException，而非以 1s 超时继续执行
        JoyaClient joya = JoyaClient.create(dataSource);
        List<Object[]> batchRows = List.of(
                new Object[]{10L, 1L, "x", 1},
                new Object[]{11L, 1L, "y", 2});
        assertThrows(TxTimeoutException.class, () ->
                joya.tx().timeout(Duration.ofMillis(15)).run(tx -> {
                    try {
                        Thread.sleep(35L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tx.sql("INSERT INTO t_rh_item (id, user_id, title, score) VALUES (?, ?, ?, ?)")
                            .batch(batchRows, 1);
                }));
    }

    @Test
    void dialectStrategyCachedFromProvider_probesOnlyOnceUnderConcurrentColdStart() throws Exception {
        AtomicInteger acquires = new AtomicInteger();
        DataSourceConnectionProvider base = new DataSourceConnectionProvider(dataSource);
        ConnectionProvider slowProvider = new ConnectionProvider() {
            @Override
            public Connection acquire() throws SQLException {
                acquires.incrementAndGet();
                try {
                    Thread.sleep(15L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return base.acquire();
            }

            @Override
            public void release(Connection connection) throws SQLException {
                base.release(connection);
            }
        };

        DialectStrategy strategy = DialectStrategy.cachedFromProvider();
        int threads = 24;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Dialect>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return strategy.resolve(slowProvider);
                }));
            }
            Dialect first = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Dialect> f : futures) {
                assertSame(first, f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, acquires.get(), "Single-flight dialect probe must borrow only 1 Connection");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void beanRowMapper_handlesTurkishLocaleChainedSettersRecordsAndTranslatesSqlException() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            JoyaClient joya = JoyaClient.create(dataSource);
            List<TurkishColumnBean> beans = joya.sql("SELECT user_id, title, score FROM t_rh_item ORDER BY id")
                    .list(TurkishColumnBean.class);
            assertEquals(2, beans.size());
            assertEquals(100L, beans.get(0).getUserId());
            assertEquals("alpha", beans.get(0).getTitle());
            assertEquals(10, beans.get(0).getScore());
            // NULL 基本类型列自动回退为 0
            assertEquals(200L, beans.get(1).getUserId());
            assertEquals(0, beans.get(1).getScore());

            List<ItemSummaryRecord> records = joya.sql("SELECT id, title, score FROM t_rh_item ORDER BY id")
                    .list(ItemSummaryRecord.class);
            assertEquals(2, records.size());
            assertEquals(new ItemSummaryRecord(1L, "alpha", 10), records.get(0));
            assertEquals(new ItemSummaryRecord(2L, "beta", 0), records.get(1));
        } finally {
            Locale.setDefault(previous);
        }

        // RowMapper 读取列阶段抛出 SQLException 必须走 SqlExceptionTranslator
        RuntimeException customTranslated = new IllegalStateException("translated-row-error");
        JoyaClient customClient = JoyaClient.builder(dataSource)
                .exceptionTranslator((task, stmt, ex) -> customTranslated)
                .build();
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                customClient.sql("SELECT id FROM t_rh_item")
                        .list(rs -> {
                            throw new SQLException("cursor read failed");
                        }));
        assertSame(customTranslated, thrown);
    }

    @Test
    void positionalRowMapper_readsEachColumnOnlyOnceOnForwardOnlyCursor() {
        record ProjectedRow(Integer id, Long userId, BigDecimal amount, LocalDateTime createdAt, Role role) {
        }

        Object[] rowValues = {
                42L,
                99L,
                new BigDecimal("12.50"),
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 12, 30)),
                "ADMIN"
        };
        int[] readCounts = new int[rowValues.length + 1];
        ResultSet oneShotRs = createSingleRowForwardOnlyResultSet(rowValues, readCounts);

        JoyaClient oneShotClient = clientWithSingleResultSet(oneShotRs);
        ProjectedRow mapped = oneShotClient.from(RhItem.class)
                .select(
                        Exprs.raw("c1", Integer.class),
                        RhItem::getUserId,
                        Exprs.raw("c3", BigDecimal.class),
                        Exprs.raw("c4", LocalDateTime.class),
                        Exprs.raw("c5", Role.class))
                .one(ProjectedRow::new)
                .orElseThrow();

        assertEquals(42, mapped.id());
        assertEquals(99L, mapped.userId());
        assertEquals(new BigDecimal("12.50"), mapped.amount());
        assertEquals(LocalDateTime.of(2026, 5, 1, 12, 30), mapped.createdAt());
        assertEquals(Role.ADMIN, mapped.role());
        for (int i = 1; i <= rowValues.length; i++) {
            assertEquals(1, readCounts[i], "Column " + i + " must be read exactly once");
        }

        // 整型窄化溢出仍然精确抛 DbException，且只读取一次底层列
        int[] overflowCounts = new int[2];
        ResultSet overflowRs = createSingleRowForwardOnlyResultSet(new Object[]{Long.MAX_VALUE}, overflowCounts);
        JoyaClient overflowClient = clientWithSingleResultSet(overflowRs);
        assertThrows(DbException.class, () ->
                overflowClient.from(RhItem.class)
                        .select(Exprs.raw("c1", Integer.class), Exprs.raw("c2", String.class))
                        .one((Integer a, String b) -> a));
        assertEquals(1, overflowCounts[1]);
    }

    @Test
    void sqlDataUtils_freesBlobAndClobAndHandlesZeroLengthBlob() throws SQLException {
        AtomicInteger emptyBlobFrees = new AtomicInteger();
        Blob emptyBlob = (Blob) Proxy.newProxyInstance(
                Blob.class.getClassLoader(),
                new Class<?>[]{Blob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "length" -> 0L;
                    case "getBytes" -> throw new AssertionError("getBytes must not be called when length == 0");
                    case "free" -> {
                        emptyBlobFrees.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });

        ResultSet emptyBlobRs = singleValueResultSet(emptyBlob);
        byte[] emptyBytes = (byte[]) SqlDataUtils.getResultSetValue(emptyBlobRs, 1, (Class<?>) null);
        assertEquals(0, emptyBytes.length);
        assertEquals(1, emptyBlobFrees.get());

        AtomicInteger clobFrees = new AtomicInteger();
        Clob clob = (Clob) Proxy.newProxyInstance(
                Clob.class.getClassLoader(),
                new Class<?>[]{Clob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCharacterStream" -> new StringReader("hello");
                    case "free" -> {
                        clobFrees.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });
        ResultSet clobRs = singleValueResultSet(clob);
        assertEquals("hello", SqlDataUtils.getResultSetValue(clobRs, 1, (Class<?>) null));
        assertEquals(1, clobFrees.get());
    }

    @Test
    void lambdaUtils_isolatesStatefulCapturingImplementations() {
        StatefulGetter idGetter = new StatefulGetter(false);
        StatefulGetter titleGetter = new StatefulGetter(true);

        assertEquals("id", LambdaUtils.getPropertyName(idGetter));
        assertEquals("title", LambdaUtils.getPropertyName(titleGetter));
    }

    @Test
    void ast20AndDefensiveUtilities_formatInjectedColumnsPreserveIdentityAndCloneArrays() throws Exception {
        // 1. SqlNodeTransformer 未改动子树时保持原引用零分配
        JoyaClient plain = JoyaClient.create(dataSource);
        SqlNode originalAst = plain.from(RhItem.class)
                .where(w -> w.eq(RhItem::getUserId, 100L))
                .toAstStatement()
                .ast();
        SqlNode untouched = new SqlNodeTransformer() {
        }.transform(originalAst);
        assertSame(originalAst, untouched);

        // 2. NamingPolicy 在渲染期统一格式化查询列、聚合列以及 SqlRewriter 动态注入的列
        plain.sql("DROP TABLE IF EXISTS \"T_UPPER_TENANT\"").update();
        plain.sql("CREATE TABLE \"T_UPPER_TENANT\" (\"ID\" BIGINT PRIMARY KEY, \"NAME\" VARCHAR(32), \"AMOUNT\" INT, \"TENANT_ID\" VARCHAR(16))").update();
        plain.sql("INSERT INTO \"T_UPPER_TENANT\" VALUES (1, 'a', 15, 't1'), (2, 'a', 40, 't2'), (3, 'a', 25, 't1')").update();

        List<String> renderedSqls = new ArrayList<>();
        SqlRewriter tenantRewriter = new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext ctx) {
                return true;
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
                if (node instanceof SqlNode.Select select) {
                    SqlNode.Table root = select.from() instanceof SqlNode.From f ? f.root() : (SqlNode.Table) select.from();
                    SqlNode pred = SqlNodes.eq(
                            SqlNodes.column(root, "tenant_id"),
                            SqlNodes.param("tenantId", "t1", ParameterSource.TENANT));
                    return select.withWhere(SqlNodes.and(select.where(), pred));
                }
                return node;
            }
        };

        JoyaClient quotedUpper = JoyaClient.builder(dataSource)
                .dialect(DbType.postgresql)
                .settings(JoyaSettings.DEFAULT.withNamingPolicy(new NamingPolicy(10, true, true).withQuotes('"', '"')))
                .rewriter(tenantRewriter)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        renderedSqls.add(ctx.statement().sql());
                    }
                })
                .build();

        Integer maxAmount = quotedUpper.from(UpperTenantEntity.class)
                .where(w -> w.eq(UpperTenantEntity::getName, "a"))
                .select(Exprs.max(UpperTenantEntity::getAmount))
                .one()
                .orElseThrow();
        assertEquals(25, maxAmount);
        assertTrue(renderedSqls.get(0).contains("MAX(t0.\"AMOUNT\")"), renderedSqls.get(0));
        assertTrue(renderedSqls.get(0).contains("t0.\"TENANT_ID\" = ?"), renderedSqls.get(0));

        // 3. MutationResult 防御性拷贝 int[]
        int[] raw = {1, 1};
        MutationResult mr = MutationResult.ofBatch(raw);
        raw[0] = 99;
        assertArrayEquals(new int[]{1, 1}, mr.batchCounts());
        mr.batchCounts()[1] = 88;
        assertArrayEquals(new int[]{1, 1}, mr.batchCounts());

        // 4. LoggingOutputStream 支持多字节 UTF-8、CRLF 剥离与尾行刷盘
        List<String> loggedLines = new ArrayList<>();
        Logger proxyLogger = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if ("info".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof String s) {
                        loggedLines.add(s);
                    }
                    return null;
                });
        try (LoggingOutputStream out = new LoggingOutputStream(proxyLogger, Level.INFO)) {
            byte[] payload = "第一行中文\r\n第二行尾随无换行".getBytes(StandardCharsets.UTF_8);
            out.write(payload, 0, 7);
            out.write(payload, 7, payload.length - 7);
        }
        assertEquals(List.of("第一行中文", "第二行尾随无换行"), loggedLines);

        // 5. BinaryOp 左结合减法右括号保护与 SqlNode.Insert 内层行列表深冻结
        SqlNode colA = SqlNodes.column((SqlNode.Table) null, "a");
        SqlNode colB = SqlNodes.column((SqlNode.Table) null, "b");
        SqlNode colC = SqlNodes.column((SqlNode.Table) null, "c");
        assertEquals("a - (b + c)",
                com.sondertara.joya.core.kernel.statement.SqlRenderer.render(
                        SqlNodes.sub(colA, SqlNodes.add(colB, colC)),
                        com.sondertara.joya.core.kernel.statement.StatementMetadata.empty()).sql());
        assertEquals("a - (b - c)",
                com.sondertara.joya.core.kernel.statement.SqlRenderer.render(
                        SqlNodes.sub(colA, SqlNodes.sub(colB, colC)),
                        com.sondertara.joya.core.kernel.statement.StatementMetadata.empty()).sql());
        assertEquals("a - b - c",
                com.sondertara.joya.core.kernel.statement.SqlRenderer.render(
                        SqlNodes.sub(SqlNodes.sub(colA, colB), colC),
                        com.sondertara.joya.core.kernel.statement.StatementMetadata.empty()).sql());
        assertEquals("(a = b) = ?",
                com.sondertara.joya.core.kernel.statement.SqlRenderer.render(
                        SqlNodes.eq(SqlNodes.eq(colA, colB), SqlNodes.param("flag", true, ParameterSource.WHERE)),
                        com.sondertara.joya.core.kernel.statement.StatementMetadata.empty()).sql());

        List<SqlNode> mutableRow = new ArrayList<>();
        mutableRow.add(SqlNodes.param("id", 1L, ParameterSource.INSERT));
        SqlNode.Insert frozenInsert = new SqlNode.Insert(
                SqlNodes.table("t_rh_item", "t_rh_item"),
                List.of("id"),
                List.of(mutableRow));
        mutableRow.add(SqlNodes.param("title", "mutated", ParameterSource.INSERT));
        assertEquals(1, frozenInsert.rows().get(0).size(), "Insert.rows 内层行列表必须深拷贝冻结");
        assertThrows(UnsupportedOperationException.class,
                () -> frozenInsert.rows().get(0).add(colA));
    }

    private static final class CountingConnectionProvider implements ConnectionProvider {
        private final DataSourceConnectionProvider delegate;
        private final AtomicInteger activeConnections = new AtomicInteger();

        private CountingConnectionProvider(JdbcDataSource ds) {
            this.delegate = new DataSourceConnectionProvider(ds);
        }

        @Override
        public Connection acquire() throws SQLException {
            Connection conn = delegate.acquire();
            activeConnections.incrementAndGet();
            return conn;
        }

        @Override
        public void release(Connection connection) throws SQLException {
            activeConnections.decrementAndGet();
            delegate.release(connection);
        }

        @Override
        public void discard(Connection connection) throws SQLException {
            activeConnections.decrementAndGet();
            delegate.discard(connection);
        }
    }

    private static ResultSet singleValueResultSet(Object value) {
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (p, m, a) -> "getColumnCount".equals(m.getName()) ? 1 : null);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> meta;
                    case "getObject" -> value;
                    case "getBlob" -> value instanceof Blob b ? b : null;
                    case "getClob" -> value instanceof Clob c ? c : null;
                    case "wasNull" -> value == null;
                    default -> null;
                });
    }

    private JoyaClient clientWithSingleResultSet(ResultSet rs) {
        return JoyaClient.builder(dataSource)
                .dialect(DbType.h2)
                .executorFactory((connections, translator) -> (com.sondertara.joya.core.kernel.executor.Executor) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.sondertara.joya.core.kernel.executor.Executor.class},
                        (proxy, method, args) -> {
                            if ("query".equals(method.getName()) && args != null && args.length == 2
                                    && args[1] instanceof com.sondertara.joya.core.kernel.executor.ResultSetExtractor<?> extractor) {
                                return extractor.extract(rs);
                            }
                            if ("queryList".equals(method.getName()) && args != null && args.length == 2
                                    && args[1] instanceof com.sondertara.joya.core.kernel.executor.RowMapper<?> rowMapper) {
                                var bound = rowMapper.forResultSet(rs);
                                List<Object> rows = new ArrayList<>(1);
                                while (rs.next()) {
                                    rows.add(bound.map(rs));
                                }
                                return rows;
                            }
                            return null;
                        }))
                .build();
    }

    private static ResultSet createSingleRowForwardOnlyResultSet(Object[] values, int[] readCounts) {
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> values.length;
                    case "getColumnTypeName" -> "";
                    case "getColumnClassName" -> {
                        int col = (Integer) args[0];
                        Object v = values[col - 1];
                        yield v == null ? Object.class.getName() : v.getClass().getName();
                    }
                    default -> 0;
                });
        final int[] cursor = {0};
        final boolean[] lastWasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("next".equals(name)) {
                        return cursor[0]++ == 0;
                    }
                    if ("getMetaData".equals(name)) {
                        return meta;
                    }
                    if ("wasNull".equals(name)) {
                        return lastWasNull[0];
                    }
                    if (name.startsWith("get") && args != null && args.length >= 1 && args[0] instanceof Integer col) {
                        if (++readCounts[col] > 1) {
                            throw new SQLException("Column " + col + " was read more than once (" + name + ")");
                        }
                        Object val = values[col - 1];
                        lastWasNull[0] = (val == null);
                        if (val == null) {
                            return switch (name) {
                                case "getByte" -> (byte) 0;
                                case "getShort" -> (short) 0;
                                case "getInt" -> 0;
                                case "getLong" -> 0L;
                                case "getFloat" -> 0f;
                                case "getDouble" -> 0d;
                                case "getBoolean" -> false;
                                default -> null;
                            };
                        }
                        return switch (name) {
                            case "getString" -> val.toString();
                            case "getLong" -> ((Number) val).longValue();
                            case "getInt" -> ((Number) val).intValue();
                            case "getBigDecimal" -> (BigDecimal) val;
                            case "getTimestamp" -> (Timestamp) val;
                            case "getObject" -> {
                                if (args.length == 2 && args[1] == LocalDateTime.class && val instanceof Timestamp ts) {
                                    yield ts.toLocalDateTime();
                                }
                                yield val;
                            }
                            default -> val;
                        };
                    }
                    return null;
                });
    }
}
