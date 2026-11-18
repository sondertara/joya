package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.jdbc.resultset.mapper.DefaultRowMapperFactory;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.tx.Tx;
import com.sondertara.joya.core.tx.TxException;
import com.sondertara.joya.core.tx.TxTimeoutException;
import com.sondertara.joya.spring.autoconfigure.JoyaAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.spring.autoconfigure.JoyaNamedClientsAutoConfiguration;
import com.sondertara.joya.spring.support.SpringConnectionProvider;
import com.sondertara.joya.spring.support.SpringSqlExceptionTranslator;
import com.sondertara.joya.spring.support.SpringTxEngine;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.core.InfrastructureProxy;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.javapoet.ClassName;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import javax.sql.DataSource;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 侧审计修复回归：外层事务连接控制守卫、循环依赖扩展点的排序可靠性。
 */
class AuditSpringRegressionTest {

    @Entity
    @Table(name = "r7")
    public static class E {
        @Id
        @Column(name = "id")
        private Long id;

        public Long getId() {
            return id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SimpleConfig {
    }

    static ApplicationContextRunner runner(String db) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SimpleConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + db + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource");
    }

    @Test
    void withConnection_blocksTransactionControlInsideAmbientSpringTransaction() {
        runner("audit_b4").run(ctx -> {
            JoyaClient joya = ctx.getBean(JoyaClient.class);
            PlatformTransactionManager tm = ctx.getBean(PlatformTransactionManager.class);
            new TransactionTemplate(tm).execute(status -> {
                boolean[] autoCommit = new boolean[1];
                joya.withConnection(conn -> {
                    autoCommit[0] = conn.getAutoCommit();
                    assertThrows(UnsupportedOperationException.class, conn::setSavepoint);
                    assertThrows(UnsupportedOperationException.class, conn::commit);
                    assertThrows(UnsupportedOperationException.class, () -> conn.setAutoCommit(true));
                    return null;
                });
                assertFalse(autoCommit[0], "外层事务连接必须是 autoCommit=false");
                return null;
            });
        });
    }

    public static class ARewriter implements SqlRewriter {
        @Override
        public int order() {
            return 0;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            CycleConfig.CALLS.add("A");
            return node;
        }
    }

    public static class ZRewriter implements SqlRewriter {
        @Override
        public int order() {
            return -100;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            CycleConfig.CALLS.add("Z");
            return node;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CycleConfig {
        static final List<String> CALLS = new CopyOnWriteArrayList<>();

        @Bean
        SqlRewriter zRewriter(JoyaClient circularClient) {
            return new ZRewriter();
        }

        @Bean
        SqlRewriter aRewriter() {
            return new ARewriter();
        }
    }

    @Test
    void circularExtensionBean_keepsDeclaredOrderAtFirstExecution() {
        new ApplicationContextRunner()
                .withUserConfiguration(CycleConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:audit_b7;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null, "context failed: " + ctx.getStartupFailure());
                    SqlRewriter z = ctx.getBean("zRewriter", SqlRewriter.class);
                    assertEquals(-100, z.order());
                    JoyaClient joya = ctx.getBean(JoyaClient.class);
                    joya.sql("CREATE TABLE r7 (id BIGINT PRIMARY KEY)").update();
                    CycleConfig.CALLS.clear();
                    joya.from(E.class).list();
                    assertEquals(List.of("Z", "A"), CycleConfig.CALLS,
                            "order()=-100 的改写器必须先执行");
                    assertTrue(CycleConfig.CALLS.size() == 2);
                });
    }

    // ===== P0-2.1 & P0-2.2: 跨数据源 SpringTxEngine 泄漏防护与交错重入同步器属主守卫 =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class DualManagedDataSourceConfig {
        @Primary
        @Bean(name = "primaryDs")
        DataSource primaryDs() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:dual_tx_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean(name = "primaryTxManager")
        DataSourceTransactionManager primaryTxManager(@Qualifier("primaryDs") DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean(name = "orderDs")
        DataSource orderDs() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:dual_tx_order;MODE=MYSQL;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }

        @Bean(name = "orderTxManager")
        DataSourceTransactionManager orderTxManager(@Qualifier("orderDs") DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    @Test
    void crossDataSourceSpringTx_rollsBackAndUnbindsConnectionWhenOuterPrimaryCompletesFirst_andGuardsInterleavedCallbacks() {
        new ApplicationContextRunner()
                .withUserConfiguration(DualManagedDataSourceConfig.class)
                .withPropertyValues(
                        "joya.primary.data-source-bean=primaryDs",
                        "joya.primary.transaction-manager-bean=primaryTxManager",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].data-source-bean=orderDs",
                        "joya.datasources[0].transaction-manager-bean=orderTxManager")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    JoyaClient primary = ctx.getBean(JoyaClient.class);
                    JoyaClient order = ctx.getBean("order", JoyaClient.class);
                    DataSource orderDs = ctx.getBean("orderDs", DataSource.class);
                    PlatformTransactionManager primaryTm = ctx.getBean("primaryTxManager", PlatformTransactionManager.class);

                    primary.sql("DROP TABLE IF EXISTS t_p").update();
                    primary.sql("CREATE TABLE t_p (id BIGINT PRIMARY KEY)").update();
                    order.sql("DROP TABLE IF EXISTS t_o").update();
                    order.sql("CREATE TABLE t_o (id BIGINT PRIMARY KEY)").update();

                    // 1. [P0-2.1] 外层 primary 先提交清空全局 actualTransactionActive，内层未提交的 orderTx.close() 必须依然回滚并解绑 ConnectionHolder
                    TransactionStatus outerStatus = primaryTm.getTransaction(new DefaultTransactionDefinition());
                    Tx orderTx = order.tx().begin();
                    try {
                        orderTx.sql("INSERT INTO t_o VALUES (1)").update();
                        primaryTm.commit(outerStatus);
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    } finally {
                        orderTx.close();
                    }
                    assertNull(TransactionSynchronizationManager.getResource(orderDs),
                            "orderTx.close() 必须解绑 orderDs 的 ConnectionHolder，严禁残留在工作线程 ThreadLocal 中");
                    assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                            "乱序关闭后不得残留 resume() 恢复的僵尸同步快照");
                    assertEquals(0L, order.sql("SELECT COUNT(*) FROM t_o").value(Long.class).orElseThrow(),
                            "未提交的 orderTx 在 close() 时必须完成物理回滚");

                    // 1b. [R1 对称性] 外层 primary 先提交结束，内层逃逸的 orderTx 显式调用 commit() 必须成功落库、触发本地 afterCommit 且无僵尸同步快照残留
                    AtomicInteger escapedOrderAfterCommit = new AtomicInteger();
                    TransactionStatus outerCommitFirst = primaryTm.getTransaction(new DefaultTransactionDefinition());
                    Tx escapedOrderCommitTx = order.tx().begin();
                    try {
                        escapedOrderCommitTx.afterCommit(escapedOrderAfterCommit::incrementAndGet);
                        escapedOrderCommitTx.sql("INSERT INTO t_o VALUES (100)").update();
                        primaryTm.commit(outerCommitFirst);
                        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
                        assertDoesNotThrow(escapedOrderCommitTx::commit,
                                "外层异源事务先结束时，内层新建事务显式 commit() 不得抛裸 IllegalStateException");
                    } finally {
                        escapedOrderCommitTx.close();
                    }
                    assertEquals(1, escapedOrderAfterCommit.get(), "逃逸新建事务显式 commit() 后本地 afterCommit 必须正常触发");
                    assertNull(TransactionSynchronizationManager.getResource(orderDs),
                            "显式 commit() 后必须解绑 orderDs 的 ConnectionHolder");
                    assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                            "乱序 commit() 后不得残留 resume() 恢复的僵尸同步快照");
                    assertEquals(1L, order.sql("SELECT COUNT(*) FROM t_o WHERE id = 100").value(Long.class).orElseThrow(),
                            "显式 commit() 的数据必须真实持久化");
                    order.sql("DELETE FROM t_o WHERE id = 100").update();

                    // 2. [P0-2.2 正向] primary 外层 -> order 中层 -> order 内层 join：同步器归属 order，afterCommit 正常在 order 提交时触发
                    AtomicInteger sameDbJoinedAfterCommit = new AtomicInteger();
                    TransactionStatus outer2 = primaryTm.getTransaction(new DefaultTransactionDefinition());
                    try {
                        order.tx(o1 -> {
                            o1.sql("INSERT INTO t_o VALUES (2)").update();
                            order.tx(o2 -> o2.afterCommit(sameDbJoinedAfterCommit::incrementAndGet));
                        });
                        assertEquals(1, sameDbJoinedAfterCommit.get(),
                                "order 独立提交后，join 到 order 的内层 afterCommit 应立即触发");
                    } finally {
                        primaryTm.rollback(outer2);
                    }
                    assertEquals(1L, order.sql("SELECT COUNT(*) FROM t_o").value(Long.class).orElseThrow());

                    // 3. [P0-2.2 反向交错重入] primary 外层 -> order 中层 -> primary 内层 join：拒绝把 primary 回调错挂到 order 同步器列表
                    TransactionStatus outer3 = primaryTm.getTransaction(new DefaultTransactionDefinition());
                    try {
                        primary.sql("INSERT INTO t_p VALUES (10)").update();
                        order.tx(o1 -> primary.tx(joinedPrimary -> {
                            assertThrows(TxException.class, () -> joinedPrimary.afterCommit(() -> {}));
                            assertThrows(TxException.class, () -> joinedPrimary.afterCompletion(out -> {}));
                            joinedPrimary.sql("INSERT INTO t_p VALUES (11)").update();
                        }));
                        primaryTm.commit(outer3);
                    } catch (Throwable t) {
                        if (!outer3.isCompleted()) {
                            primaryTm.rollback(outer3);
                        }
                        throw t;
                    }
                    assertEquals(2L, primary.sql("SELECT COUNT(*) FROM t_p").value(Long.class).orElseThrow(),
                            "order 退出后外层 primary 提交应正常持久化两行写入");
                });
    }

    // ===== P0-3: 无数据源微服务自动退让 =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class DatabaseFreeServiceConfig {
    }

    @Test
    void noDataSourceService_backsOffGracefullyWithoutStartupCrash() {
        new ApplicationContextRunner()
                .withUserConfiguration(DatabaseFreeServiceConfig.class)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "排除 DataSourceAutoConfiguration 的无库服务不得启动崩溃");
                    assertTrue(ctx.getBeansOfType(JoyaClientRegistry.class).isEmpty());
                    assertTrue(ctx.getBeansOfType(JoyaClient.class).isEmpty());
                });
    }

    // ===== P0-4: 漏填 url 非 NPE 自解释报错 + hikari 嵌套绑定成功与拼写错误 Fail-Fast =====

    public static class MockPoolDataSource extends AbstractDataSource {
        private String url;
        private int maximumPoolSize = 10;
        private Properties dataSourceProperties = new Properties();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public void setUsername(String username) {
        }

        public void setPassword(String password) {
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public Properties getDataSourceProperties() {
            return dataSourceProperties;
        }

        public void setDataSourceProperties(Properties dataSourceProperties) {
            this.dataSourceProperties = dataSourceProperties;
        }

        @Override
        public Connection getConnection() throws SQLException {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL(url);
            h2.setUser("sa");
            return h2.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }
    }

    @Test
    void selfManagedDataSource_missingUrlThrowsActionableErrorNotNpe_andHikariBindsNestedPropsWhileFailingOnTypos() {
        // 1. 漏填 url -> 明确提示 .url 缺失，绝不抛 NullPointerException
        runner("p0_4_missing_url")
                .withPropertyValues("joya.datasources[0].name=order")
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure);
                    Throwable root = rootCause(failure);
                    assertInstanceOf(IllegalStateException.class, root);
                    assertTrue(root.getMessage().contains("joya.datasources[0].url"), root.getMessage());
                });

        // 2. 合法标量 + 嵌套 data-source-properties 端到端成功绑定
        runner("p0_4_hikari_ok")
                .withPropertyValues(
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:p0_4_order;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].type=" + MockPoolDataSource.class.getName(),
                        "joya.datasources[0].hikari.maximum-pool-size=24",
                        "joya.datasources[0].hikari.data-source-properties.rewriteBatchedStatements=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "unexpected failure: " + ctx.getStartupFailure());
                    JoyaClient order = ctx.getBean("order", JoyaClient.class);
                    MockPoolDataSource pool = assertInstanceOf(MockPoolDataSource.class,
                            order.withConnection(conn -> null) == null
                                    ? extractOriginDataSource(order)
                                    : null);
                    assertEquals(24, pool.getMaximumPoolSize());
                    assertEquals("true", String.valueOf(pool.getDataSourceProperties().get("rewriteBatchedStatements")));
                });

        // 3. 拼写错误属性（max-pool-size）立即 Fail-Fast 拒绝，不再静默忽略
        runner("p0_4_hikari_typo")
                .withPropertyValues(
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:p0_4_typo;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].type=" + MockPoolDataSource.class.getName(),
                        "joya.datasources[0].hikari.max-pool-size=99")
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure, "拼写错误的 hikari 属性必须启动失败");
                    assertTrue(failure.getMessage().contains("joya.datasources[0].hikari")
                            || rootCause(failure).getMessage().contains("max-pool-size"));
                });
    }

    private static DataSource extractOriginDataSource(JoyaClient client) {
        try {
            var kernelField = JoyaClient.class.getDeclaredField("kernel");
            kernelField.setAccessible(true);
            var kernel = (com.sondertara.joya.core.kernel.ExecutionKernel) kernelField.get(client);
            return kernel.connections().unwrap().originDataSource();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ===== P1-1 & P1-2: 读取期 SQLException 经过 Translator 翻译 + 池耗尽故障期零二次借连接 =====

    @Test
    void resultSetFetchAndStreamErrors_areTranslatedToDataAccessException_andOutageDoesNotDoubleBorrowConnection() {
        // 1. P1-2: 连接池不可用时，SpringSqlExceptionTranslator 零连接探测直接翻译，绝不二次 getConnection()
        AtomicInteger getConnCalls = new AtomicInteger();
        DataSource outageDs = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        getConnCalls.incrementAndGet();
                        throw new SQLTimeoutException("Pool exhausted", "08001");
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
        JoyaClient outageClient = JoyaClient.builder(outageDs)
                .dialect(com.sondertara.joya.core.kernel.statement.DbType.mysql)
                .exceptionTranslator(new SpringSqlExceptionTranslator(outageDs))
                .build();
        assertThrows(DataAccessException.class, () -> outageClient.sql("SELECT 1").value(Integer.class));
        assertEquals(1, getConnCalls.get(), "故障翻译阶段严禁二次调用 dataSource.getConnection()");

        // 2. P1-1: ResultSet.next() 拉取阶段（list / one / stream）抛出的 SQLTimeoutException 必须翻译为 QueryTimeoutException
        JdbcDataSource rawH2 = new JdbcDataSource();
        rawH2.setURL("jdbc:h2:mem:p1_1_fetch_err;DB_CLOSE_DELAY=-1");
        rawH2.setUser("sa");
        DataSource fetchFailDs = wrapResultSetNextTimeout(rawH2);
        JoyaClient fetchClient = JoyaClient.builder(fetchFailDs)
                .dialect(com.sondertara.joya.core.kernel.statement.DbType.h2)
                .exceptionTranslator(new SpringSqlExceptionTranslator(fetchFailDs))
                .build();

        assertThrows(QueryTimeoutException.class, () -> fetchClient.sql("SELECT 1").listMap());
        assertThrows(QueryTimeoutException.class, () -> fetchClient.sql("SELECT 1").one(Integer.class));
        assertThrows(QueryTimeoutException.class, () -> {
            try (Stream<Integer> s = fetchClient.sql("SELECT 1").stream(Integer.class)) {
                s.toList();
            }
        });
    }

    private static DataSource wrapResultSetNextTimeout(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (dsProxy, dsMethod, dsArgs) -> {
                    if ("equals".equals(dsMethod.getName())) {
                        return dsProxy == dsArgs[0];
                    }
                    if ("hashCode".equals(dsMethod.getName())) {
                        return System.identityHashCode(dsProxy);
                    }
                    Object dsRes = invokeRaw(dsMethod, target, dsArgs);
                    if ("getConnection".equals(dsMethod.getName()) && dsRes instanceof Connection conn) {
                        return (Connection) Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                (cProxy, cMethod, cArgs) -> {
                                    Object cRes = invokeRaw(cMethod, conn, cArgs);
                                    if ("prepareStatement".equals(cMethod.getName()) && cRes instanceof PreparedStatement ps) {
                                        return (PreparedStatement) Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[]{PreparedStatement.class},
                                                (pProxy, pMethod, pArgs) -> {
                                                    Object pRes = invokeRaw(pMethod, ps, pArgs);
                                                    if ("executeQuery".equals(pMethod.getName()) && pRes instanceof ResultSet rs) {
                                                        return (ResultSet) Proxy.newProxyInstance(
                                                                ResultSet.class.getClassLoader(),
                                                                new Class<?>[]{ResultSet.class},
                                                                (rProxy, rMethod, rArgs) -> {
                                                                    if ("next".equals(rMethod.getName())) {
                                                                        throw new SQLTimeoutException("Cursor fetch timed out", "57014");
                                                                    }
                                                                    return invokeRaw(rMethod, rs, rArgs);
                                                                });
                                                    }
                                                    return pRes;
                                                });
                                    }
                                    return cRes;
                                });
                    }
                    return dsRes;
                });
    }

    private static Object invokeRaw(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    // ===== P1-3: Lambda @Bean @Order 排序 + 无值自定义 @Qualifier 元注解隔离 =====

    @Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER, ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface InternalSpecialOnly {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SpringOrderAndCustomQualifierConfig {
        static final List<String> EXEC_ORDER = new CopyOnWriteArrayList<>();
        static final AtomicInteger GLOBAL_MAPPER_CALLS = new AtomicInteger();
        static final AtomicInteger INTERNAL_MAPPER_CALLS = new AtomicInteger();

        @Bean
        @Order(50)
        SqlRewriter lateLambdaRewriter() {
            return (node, ctx) -> {
                EXEC_ORDER.add("late-50");
                return node;
            };
        }

        @Bean
        @Order(-50)
        SqlRewriter earlyLambdaRewriter() {
            return (node, ctx) -> {
                EXEC_ORDER.add("early--50");
                return node;
            };
        }

        @Bean
        RowMapperFactory globalRowMapperFactory() {
            return new RowMapperFactory() {
                @Override
                public <V> RowMapper<V> create(ResultType type, Class<V> javaType) {
                    GLOBAL_MAPPER_CALLS.incrementAndGet();
                    return DefaultRowMapperFactory.INSTANCE.create(type, javaType);
                }
            };
        }

        @Bean
        @InternalSpecialOnly
        RowMapperFactory isolatedInternalMapperFactory() {
            return new RowMapperFactory() {
                @Override
                public <V> RowMapper<V> create(ResultType type, Class<V> javaType) {
                    INTERNAL_MAPPER_CALLS.incrementAndGet();
                    return DefaultRowMapperFactory.INSTANCE.create(type, javaType);
                }
            };
        }
    }

    @Test
    void springOrderAnnotationOnLambdaBeans_andCustomValuelessQualifierIsolation_workAsExpected() {
        SpringOrderAndCustomQualifierConfig.EXEC_ORDER.clear();
        SpringOrderAndCustomQualifierConfig.GLOBAL_MAPPER_CALLS.set(0);
        SpringOrderAndCustomQualifierConfig.INTERNAL_MAPPER_CALLS.set(0);

        new ApplicationContextRunner()
                .withUserConfiguration(SpringOrderAndCustomQualifierConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:p1_3_order_qual;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "unexpected conflict: " + ctx.getStartupFailure());
                    JoyaClient joya = ctx.getBean(JoyaClient.class);
                    joya.sql("CREATE TABLE r7 (id BIGINT PRIMARY KEY)").update();
                    SpringOrderAndCustomQualifierConfig.EXEC_ORDER.clear();

                    joya.from(E.class).list();
                    assertEquals(List.of("early--50", "late-50"), SpringOrderAndCustomQualifierConfig.EXEC_ORDER,
                            "Lambda @Bean 上的 Spring @Order 注解必须生效");
                    assertTrue(SpringOrderAndCustomQualifierConfig.GLOBAL_MAPPER_CALLS.get() > 0);
                    assertEquals(0, SpringOrderAndCustomQualifierConfig.INTERNAL_MAPPER_CALLS.get(),
                            "标注无值自定义 @Qualifier 元注解的 Bean 不得被误判为全局候选");
                });
    }

    // ===== P1-4 & P2-1: Spring 6 AOT 代码生成兼容 + 外层超时桥接 TxTimeoutException + InfrastructureProxy 校验 =====

    @Test
    void namedClientsAreCompatibleWithSpring6AotGeneration_andOuterTimeoutBridgesToTxTimeoutException() {
        // 1. [P1-4] 真实运行 Spring 6 ApplicationContextAotGenerator 验证命名 JoyaClient 无 InstanceSupplier 异常
        try (AnnotationConfigApplicationContext aotCtx = new AnnotationConfigApplicationContext()) {
            aotCtx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("aotTest", Map.of(
                    "spring.datasource.url", "jdbc:h2:mem:aot_primary;DB_CLOSE_DELAY=-1",
                    "spring.datasource.type", "org.h2.jdbcx.JdbcDataSource",
                    "joya.datasources[0].name", "order",
                    "joya.datasources[0].url", "jdbc:h2:mem:aot_order;DB_CLOSE_DELAY=-1",
                    "joya.datasources[0].type", "org.h2.jdbcx.JdbcDataSource"
            )));
            aotCtx.register(DataSourceAutoConfiguration.class,
                    JoyaAutoConfiguration.class,
                    JoyaNamedClientsAutoConfiguration.class);
            InMemoryGeneratedFiles generatedFiles = new InMemoryGeneratedFiles();
            ClassName initializer = assertDoesNotThrow(() ->
                    new ApplicationContextAotGenerator().processAheadOfTime(
                            aotCtx,
                            new org.springframework.aot.generate.DefaultGenerationContext(
                                    new org.springframework.aot.generate.ClassNameGenerator(
                                            ClassName.get("com.example", "AotApp")),
                                    generatedFiles)));
            assertNotNull(initializer);
        }

        // 2. [P2-1] 外层 Spring 事务超时在显式 joya.tx 中统一桥接为 TxTimeoutException，且 InfrastructureProxy 校验一致
        runner("p2_1_timeout_and_proxy").run(ctx -> {
            JoyaClient joya = ctx.getBean(JoyaClient.class);
            PlatformTransactionManager tm = ctx.getBean(PlatformTransactionManager.class);
            DataSource rawDs = ctx.getBean(DataSource.class);

            DefaultTransactionDefinition shortDef = new DefaultTransactionDefinition();
            shortDef.setTimeout(1);
            TransactionStatus outer = tm.getTransaction(shortDef);
            try {
                Thread.sleep(1150L);
                TxTimeoutException timeoutEx = assertThrows(TxTimeoutException.class,
                        () -> joya.tx(tx -> tx.sql("SELECT 1").value(Integer.class)));
                assertInstanceOf(TransactionTimedOutException.class, timeoutEx.getCause());
            } finally {
                tm.rollback(outer);
            }

            // InfrastructureProxy 包装的数据源在 validateConfiguration 中同样被识别为同源
            DataSource infraProxyDs = (DataSource) Proxy.newProxyInstance(
                    InfrastructureProxy.class.getClassLoader(),
                    new Class<?>[]{DataSource.class, InfrastructureProxy.class},
                    (proxy, method, args) -> {
                        if ("getWrappedObject".equals(method.getName())) {
                            return rawDs;
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        return invokeRaw(method, rawDs, args);
                    });
            assertDoesNotThrow(() -> JoyaClient.builder(infraProxyDs)
                    .connectionProvider(new SpringConnectionProvider(rawDs, 0))
                    .txEngine(new SpringTxEngine(rawDs, tm))
                    .build());
        });
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cur = error;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }
}
