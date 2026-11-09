package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterBinder;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;
import com.sondertara.joya.exceptions.DbException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计修复回归：别名剥离、重复列、分页契约、词法器、批量观测、操作类型、连接来源一致性、参数绑定缓存。
 */
class AuditRegressionTest {

    @Entity
    @Table(name = "r_probe")
    public static class ProbeEntity {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "status")
        private Integer status;

        @Column(name = "name")
        private String name;

        public ProbeEntity() {
        }

        public ProbeEntity(Long id, Integer status, String name) {
            this.id = id;
            this.status = status;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "r_dup")
    public static class DupEntity {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "same_col")
        private String first;

        @Column(name = "same_col")
        private String second;
    }

    @Entity
    @Table(name = "r_dup_case")
    public static class CaseInsensitiveDupEntity {
        @Id
        @Column(name = "ID")
        private Long id;

        @Column(name = "id")
        private Long duplicateId;
    }

    private static JoyaClient client(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return JoyaClient.create(ds);
    }

    @Test
    void addCondition_stripsAliasInSingleTableMode() {
        SqlStatement stmt = client("audit_b1").of(ProbeEntity.class)
                .update()
                .set(ProbeEntity::getStatus, 2)
                .where(w -> w.addCondition("t0.status = ?", 1))
                .toSqlStatement();
        assertEquals("UPDATE r_probe SET status = ? WHERE status = ?", stmt.sql());
        assertEquals(2, stmt.parameters().size());
    }

    @Test
    void duplicateColumn_failsFastInsteadOfSilentlyDroppingAField() {
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(DupEntity.class));
    }

    @Test
    void pageResult_unknownTotalDoesNotGuessHasNext() {
        assertFalse(PageResult.of(List.of(1, 2), -1L, 1, 2).hasNext());
        assertTrue(PageResult.of(List.of(1, 2), -1L, 1, 2, true).hasNext());
    }

    @Test
    void aliasStripping_keepsHashTokenSemanticsForSqlServerAndPostgres() {
        assertEquals("id IN (SELECT id FROM #tmp WHERE v = 1)",
                SqlFormatUtils.stripTableAlias("t0.id IN (SELECT id FROM #tmp WHERE t0.v = 1)"));
        assertEquals("a # b > 0", SqlFormatUtils.stripTableAlias("t0.a # t0.b > 0"));
        assertEquals("t0.col_name # t0.other_name",
                SqlFormatUtils.underlineColumn("t0.colName # t0.otherName"));
    }

    @Test
    void batchListener_seesParameterConsistentTemplate() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_b10;DB_CLOSE_DELAY=-1");
        List<SqlStatement> seen = new ArrayList<>();
        JoyaClient joya = JoyaClient.builder(ds)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        if (ctx.batch()) {
                            seen.add(ctx.statement());
                        }
                    }
                }).build();
        joya.sql("CREATE TABLE t_b10 (id BIGINT PRIMARY KEY, name VARCHAR(8))").update();
        joya.sql("INSERT INTO t_b10(id, name) VALUES (?, ?)").batch(List.<Object[]>of(
                new Object[]{1, "a"}, new Object[]{2, "b"}));

        assertEquals(1, seen.size());
        SqlStatement probe = seen.get(0);
        assertEquals(SqlFormatUtils.countPlaceholders(probe.sql(), probe.metadata().dbType()),
                probe.parameters().size(), "批量探针必须满足占位符/参数契约");
        assertEquals(2, probe.parameters().size());
    }

    @Test
    void truncateAndDdl_carryAccurateOperation() {
        var meta = LocalTableMetaCache.getMetadataContext(ProbeEntity.class);
        assertEquals(Operation.TRUNCATE,
                DeleteStatementFactory.createTruncate(meta, NamingPolicy.DEFAULT).metadata().operation());
        assertEquals(Operation.TRUNCATE, SqlFormatUtils.inferDmlOperation("TRUNCATE TABLE r_probe"));
        assertEquals(Operation.DDL, SqlFormatUtils.inferDmlOperation("CREATE TABLE t (id INT)"));
        assertEquals(Operation.DDL, SqlFormatUtils.inferDmlOperation("ALTER TABLE t ADD c INT"));
        assertEquals(Operation.DELETE, SqlFormatUtils.inferDmlOperation("DELETE FROM t WHERE id = 1"));
    }

    @Test
    void mismatchedConnectionProvider_isRejected() {
        JdbcDataSource a = new JdbcDataSource();
        a.setURL("jdbc:h2:mem:audit_a");
        JdbcDataSource b = new JdbcDataSource();
        b.setURL("jdbc:h2:mem:audit_b");
        assertThrows(IllegalArgumentException.class, () -> JoyaClient.builder(a)
                .connectionProvider(new DataSourceConnectionProvider(b))
                .build());
    }

    @Test
    void oracleProbeIsCachedPerConnection() throws Exception {
        AtomicInteger metadataCalls = new AtomicInteger();
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDatabaseProductName", "getURL" -> {
                        metadataCalls.incrementAndGet();
                        yield "SomePooledDriver";
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        Connection conn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> {
                        metadataCalls.incrementAndGet();
                        yield md;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> conn;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });

        ParameterBinder.DEFAULT.bindValue(ps, 1, null, OffsetDateTime.now());
        int afterFirstBind = metadataCalls.get();
        ParameterBinder.DEFAULT.bindValue(ps, 1, null, OffsetDateTime.now());

        assertTrue(afterFirstBind > 0);
        assertEquals(afterFirstBind, metadataCalls.get(), "同一连接的方言探测必须只发生一次");
    }

    @Test
    void transactionsStillBorrowOnlyFromConnectionProvider() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_provider;DB_CLOSE_DELAY=-1");
        ConnectionProvider provider = new DataSourceConnectionProvider(ds);
        assertEquals(ds, provider.originDataSource());
        assertTrue(provider.allowsTransactionControl());
    }

    @Test
    void mysqlLikeEscapeIsRenderedByTheSinglePipeline() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_escape;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        JoyaClient joya = JoyaClient.builder(ds).dialect(DbType.mysql).build();
        SqlStatement stmt = joya.from(ProbeEntity.class)
                .where(w -> w.contains(ProbeEntity::getName, "a_b"))
                .toSqlStatement();
        assertTrue(stmt.sql().contains("ESCAPE '\\\\'"), stmt.sql());

        // P1-1：离线构建的 Query（dbType == null）进入 joya.sql(stmt) 时必须按目标方言重渲 AST
        SqlStatement offlineLike = com.sondertara.joya.core.query.dsl.Query.of(ProbeEntity.class)
                .where(w -> w.contains(ProbeEntity::getName, "50%"))
                .toSqlStatement();
        assertEquals("SELECT t0.id, t0.status, t0.name FROM r_probe t0 WHERE t0.name LIKE ? ESCAPE '\\'", offlineLike.sql());
        List<String> executedSql = new ArrayList<>();
        JoyaClient mysqlClient = JoyaClient.builder(ds)
                .dialect(DbType.mysql)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        executedSql.add(ctx.statement().sql());
                    }
                })
                .executorFactory((connections, translator) -> (com.sondertara.joya.core.kernel.executor.Executor) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.sondertara.joya.core.kernel.executor.Executor.class},
                        (proxy, method, args) -> "queryList".equals(method.getName()) ? List.of() : null))
                .build();
        mysqlClient.sql(offlineLike).list(ProbeEntity.class);
        assertEquals(1, executedSql.size());
        assertTrue(executedSql.get(0).contains("ESCAPE '\\\\'"), executedSql.get(0));

        // 离线构建含 FOR UPDATE 的 Query 进入 SQLite 方言客户端时，重渲必须触发 UnsupportedOperationException
        SqlStatement offlineForUpdate = com.sondertara.joya.core.query.dsl.Query.of(ProbeEntity.class)
                .forUpdate()
                .toSqlStatement();
        JoyaClient sqliteClient = JoyaClient.builder(ds).dialect(DbType.sqlite).build();
        assertThrows(UnsupportedOperationException.class,
                () -> sqliteClient.sql(offlineForUpdate).list(ProbeEntity.class));
    }

    @Test
    void duplicateColumnDetection_isCaseInsensitive() {
        DbException ex = assertThrows(DbException.class,
                () -> LocalTableMetaCache.getMetadataContext(CaseInsensitiveDupEntity.class));
        assertTrue(ex.getMessage().contains("Duplicate column name"), ex.getMessage());
    }

    @Test
    void perConnectionDialect_doesNotDoubleBorrowOnLimitOrFirst() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_per_conn;DB_CLOSE_DELAY=-1");
        DataSourceConnectionProvider base = new DataSourceConnectionProvider(ds);
        AtomicInteger acquires = new AtomicInteger();
        ConnectionProvider counting = new ConnectionProvider() {
            @Override
            public Connection acquire() throws java.sql.SQLException {
                acquires.incrementAndGet();
                return base.acquire();
            }

            @Override
            public void release(Connection connection) throws java.sql.SQLException {
                base.release(connection);
            }
        };
        JoyaClient joya = JoyaClient.builder(ds)
                .connectionProvider(counting)
                .dialectStrategy(com.sondertara.joya.core.kernel.dialect.DialectStrategy.perConnection())
                .build();
        joya.sql("CREATE TABLE IF NOT EXISTS r_probe (id BIGINT PRIMARY KEY, status INT, name VARCHAR(64))").update();

        acquires.set(0);
        joya.from(ProbeEntity.class).list();
        assertEquals(2, acquires.get(), "perConnection 模式下 list() 应只借还 2 次连接（1 次探测 + 1 次执行）");

        acquires.set(0);
        joya.from(ProbeEntity.class).first();
        assertEquals(2, acquires.get(), "perConnection 模式下 first() 应复用 prepare 已探测的方言，只借还 2 次连接");

        acquires.set(0);
        joya.of(ProbeEntity.class).save(new ProbeEntity(1L, 1, "one"));
        assertEquals(2, acquires.get(), "perConnection 模式下 save() 应复用单次探测的方言并注入 dbType，只借还 2 次连接");

        acquires.set(0);
        joya.of(ProbeEntity.class).saveAll(List.of(
                new ProbeEntity(1L, 2, "one-updated"),
                new ProbeEntity(2L, 1, "two")));
        assertEquals(2, acquires.get(), "perConnection 模式下 saveAll() 应复用单次探测的方言并注入 dbType，只借还 2 次连接");
    }

    @Test
    void customLambdaDialectStrategy_preservesCustomPageSqlInQueryAndSqlOps() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_lambda_dialect;DB_CLOSE_DELAY=-1");
        com.sondertara.joya.core.kernel.dialect.Dialect custom = new com.sondertara.joya.core.kernel.dialect.Dialect() {
            @Override
            public DbType dbType() {
                return DbType.h2;
            }

            @Override
            public SqlStatement countSql(SqlStatement statement) {
                return com.sondertara.joya.core.kernel.dialect.Dialect.H2.countSql(statement);
            }

            @Override
            public SqlStatement pageSql(SqlStatement statement, long offset, long limit) {
                throw new IllegalStateException("LAMBDA_CUSTOM_DIALECT_PAGE");
            }
        };
        // 使用 lambda 形式的 DialectStrategy（其默认 peek() 返回 null）
        JoyaClient joya = JoyaClient.builder(ds)
                .dialectStrategy(connections -> custom)
                .build();
        joya.sql("CREATE TABLE IF NOT EXISTS r_probe (id BIGINT PRIMARY KEY, status INT, name VARCHAR(64))").update();
        joya.sql("INSERT INTO r_probe(id, status, name) VALUES (1, 1, 'a')").update();

        IllegalStateException e1 = assertThrows(IllegalStateException.class,
                () -> joya.from(ProbeEntity.class).first());
        assertEquals("LAMBDA_CUSTOM_DIALECT_PAGE", e1.getMessage());

        IllegalStateException e2 = assertThrows(IllegalStateException.class,
                () -> joya.from(ProbeEntity.class).page(com.sondertara.joya.core.query.pagination.PageRequest.of(0, 1)));
        assertEquals("LAMBDA_CUSTOM_DIALECT_PAGE", e2.getMessage());

        IllegalStateException e3 = assertThrows(IllegalStateException.class,
                () -> joya.sql("SELECT id, status, name FROM r_probe").first(ProbeEntity.class));
        assertEquals("LAMBDA_CUSTOM_DIALECT_PAGE", e3.getMessage());

        IllegalStateException e4 = assertThrows(IllegalStateException.class,
                () -> joya.sql("SELECT id, status, name FROM r_probe")
                        .page(ProbeEntity.class, com.sondertara.joya.core.query.pagination.PageRequest.of(0, 1)));
        assertEquals("LAMBDA_CUSTOM_DIALECT_PAGE", e4.getMessage());
    }

    public static class ErrorOnWriteBean {
        private String name;

        public void setName(String name) {
            throw new AssertionError("boom-error:" + name);
        }
    }

    public record ErrorOnRecordCtor(String name) {
        public ErrorOnRecordCtor {
            throw new AssertionError("record-boom:" + name);
        }
    }

    @Test
    void beanRowMapper_propagatesJvmErrorWithoutWrappingAsDbException() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:audit_error_prop;DB_CLOSE_DELAY=-1");
        JoyaClient joya = JoyaClient.create(ds);
        AssertionError beanErr = assertThrows(AssertionError.class,
                () -> joya.sql("SELECT 'x' AS name").one(ErrorOnWriteBean.class));
        assertEquals("boom-error:x", beanErr.getMessage());

        AssertionError recordErr = assertThrows(AssertionError.class,
                () -> joya.sql("SELECT 'y' AS name").one(ErrorOnRecordCtor.class));
        assertEquals("record-boom:y", recordErr.getMessage());
    }

    @Test
    void customProvider_defaultDiscardClosesDirectly_andDoesNotPreReadBuilderDataSourceUrl() throws Exception {
        JdbcDataSource builderDsWithMysqlUrl = new JdbcDataSource();
        builderDsWithMysqlUrl.setURL("jdbc:mysql://127.0.0.1:3306/never_connect");

        JdbcDataSource actualH2 = new JdbcDataSource();
        actualH2.setURL("jdbc:h2:mem:audit_custom_provider;DB_CLOSE_DELAY=-1");
        AtomicInteger releaseCalls = new AtomicInteger();
        ConnectionProvider customProvider = new ConnectionProvider() {
            @Override
            public Connection acquire() throws java.sql.SQLException {
                return actualH2.getConnection();
            }

            @Override
            public void release(Connection connection) throws java.sql.SQLException {
                releaseCalls.incrementAndGet();
                connection.close();
            }
        };

        JoyaClient joya = JoyaClient.builder(builderDsWithMysqlUrl)
                .connectionProvider(customProvider)
                .build();
        // 自定义 provider 的 originDataSource() 为 null，不得从 builderDsWithMysqlUrl 预读出 mysql，而应经 customProvider 探测出 h2
        assertEquals(DbType.h2, joya.dbType());

        // 模拟回滚失败触发 TxConnection.close() -> discard()：仅实现 acquire/release 的自定义 provider 不得被回调 release
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger autoCommitTrueCalls = new AtomicInteger();
        Connection failingRollbackConn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> false;
                    case "setAutoCommit" -> {
                        if (Boolean.TRUE.equals(args[0])) {
                            autoCommitTrueCalls.incrementAndGet();
                        }
                        yield null;
                    }
                    case "isReadOnly" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "rollback" -> throw new java.sql.SQLException("simulated rollback error");
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        yield null;
                    }
                    default -> null;
                });
        ConnectionProvider releaseFlipsAutoCommitProvider = new ConnectionProvider() {
            @Override
            public Connection acquire() {
                return failingRollbackConn;
            }

            @Override
            public void release(Connection connection) throws java.sql.SQLException {
                releaseCalls.incrementAndGet();
                connection.setAutoCommit(true);
            }
        };
        releaseCalls.set(0);
        var txConn = new com.sondertara.joya.core.tx.TxConnection(
                releaseFlipsAutoCommitProvider.acquire(), releaseFlipsAutoCommitProvider);
        txConn.begin(com.sondertara.joya.core.tx.TxOptions.DEFAULT);
        assertThrows(java.sql.SQLException.class, txConn::close);
        assertEquals(1, closeCalls.get(), "回滚失败时默认 discard 必须直接调用 connection.close()");
        assertEquals(0, releaseCalls.get(), "回滚失败时默认 discard 严禁回调自定义 provider.release()");
        assertEquals(0, autoCommitTrueCalls.get(), "回滚失败后严禁触发 setAutoCommit(true)");

        // TransactionalConnectionProvider.discard 必须委托给 TxSession.release（不关闭事务物理连接）
        JoyaClient h2Client = JoyaClient.create(actualH2);
        h2Client.sql("CREATE TABLE IF NOT EXISTS r_probe (id BIGINT PRIMARY KEY, status INT, name VARCHAR(64))").update();
        h2Client.tx(tx -> {
            try {
                java.lang.reflect.Field dataOpsField = tx.getClass().getDeclaredField("dataOps");
                dataOpsField.setAccessible(true);
                Object txDataOps = dataOpsField.get(tx);
                java.lang.reflect.Field kernelField = txDataOps.getClass().getDeclaredField("kernel");
                kernelField.setAccessible(true);
                com.sondertara.joya.core.kernel.ExecutionKernel txKernel =
                        (com.sondertara.joya.core.kernel.ExecutionKernel) kernelField.get(txDataOps);
                ConnectionProvider txProvider = txKernel.connections();
                Connection txPhysicalConn = txProvider.acquire();
                txProvider.discard(txPhysicalConn);
                assertFalse(txPhysicalConn.isClosed(),
                        "TransactionalConnectionProvider.discard 不得直接关闭事务持有的物理连接");
            } catch (ReflectiveOperationException | java.sql.SQLException e) {
                throw new AssertionError(e);
            }
            tx.of(ProbeEntity.class).save(new ProbeEntity(99L, 1, "still-alive"));
        });
        assertTrue(h2Client.of(ProbeEntity.class).existsById(99L));
    }
}
