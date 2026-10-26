package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.spring.autoconfigure.JoyaClientCustomizer;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.spring.test.order.OrderRecord;
import com.sondertara.joya.spring.test.order.OrderRecordMapper;
import com.sondertara.joya.spring.test.user.UserAccount;
import com.sondertara.joya.spring.test.user.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joya Spring Boot 3 旁路（Sidecar）与多数据源集成测试：
 * 验证主库完全复用 Spring Boot 原生 {@code spring.datasource.*} 自动装配，
 * 扩展库通过 {@code joya.datasources.<name>.*} 独立挂载且不污染全局 {@link DataSource} Bean。
 */
@SpringBootTest(
        classes = JoyaSpringSidecarIntegrationTest.TestConfig.class,
        properties = {
                // 1. Spring Boot 原生主数据源配置（不破坏 DataSourceAutoConfiguration）
                "spring.datasource.url=jdbc:h2:mem:spring_user_db;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                // 2. Joya 主库配置（显式声明复用 Spring 原生数据源 + 主库参数）
                "joya.primary.use-spring=true",
                "joya.primary.batch-size=2048",
                // 3. Joya 扩展数据源配置（数组形式，内聚连接与 Joya 参数覆写）
                "joya.datasources[0].name=order",
                "joya.datasources[0].url=jdbc:h2:mem:spring_order_db;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                "joya.datasources[0].username=sa",
                "joya.datasources[0].password=",
                "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource",
                "joya.datasources[0].batch-size=512",
                "joya.datasources[0].query-timeout-seconds=15"
        }
)
class JoyaSpringSidecarIntegrationTest {

    @Configuration
    @EnableTransactionManagement
    @EnableAutoConfiguration
    static class TestConfig {

        static final List<String> GLOBAL_SQL_LOG = new CopyOnWriteArrayList<>();
        static final List<String> ORDER_ONLY_SQL_LOG = new CopyOnWriteArrayList<>();
        static final List<String> CUSTOMIZER_INVOKED_FOR = new CopyOnWriteArrayList<>();

        @Bean
        public SqlInterceptor globalSqlInterceptor() {
            return (ctx, chain) -> {
                GLOBAL_SQL_LOG.add(ctx.statement().sql());
                return chain.proceed(ctx);
            };
        }

        @Bean
        @Qualifier("order")
        public SqlInterceptor orderOnlySqlInterceptor() {
            return (ctx, chain) -> {
                ORDER_ONLY_SQL_LOG.add(ctx.statement().sql());
                return chain.proceed(ctx);
            };
        }

        @Bean
        public JoyaClientCustomizer testCustomizer() {
            return (clientName, ds, builder) -> CUSTOMIZER_INVOKED_FOR.add(clientName);
        }

        @Bean
        public UserTxCollaborator userTxCollaborator() {
            return new UserTxCollaborator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoDiscoveryConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingCustomizerConfig {

        static final AtomicReference<RuntimeException> FAILURE = new AtomicReference<>();

        @Bean
        JoyaClientCustomizer failingJoyaClientCustomizer() {
            return (clientName, dataSource, builder) -> {
                if ("order".equals(clientName)) {
                    RuntimeException failure = new IllegalStateException("Intentional Joya registry build failure");
                    FAILURE.set(failure);
                    throw failure;
                }
            };
        }
    }

    public static class TrackedDataSource extends org.springframework.jdbc.datasource.AbstractDataSource
            implements AutoCloseable {

        static final AtomicInteger CREATED_COUNT = new AtomicInteger();
        static final List<String> CLOSE_ORDER = new CopyOnWriteArrayList<>();

        private String url;

        public TrackedDataSource() {
            CREATED_COUNT.incrementAndGet();
        }

        public void setDriverClassName(String driverClassName) {
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public void setUsername(String username) {
        }

        public void setPassword(String password) {
        }

        static void reset() {
            CREATED_COUNT.set(0);
            CLOSE_ORDER.clear();
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            throw new java.sql.SQLException("TrackedDataSource does not provide connections");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return getConnection();
        }

        @Override
        public void close() throws Exception {
            String name = url != null && url.contains("cleanup_order") ? "order" : "primary";
            CLOSE_ORDER.add(name);
            throw new Exception("close-" + name);
        }
    }

    static class UserTxCollaborator {
        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private JoyaClient joyaClient;

        @Transactional
        public void primaryOrmWritesThenJoyaReadsAndWritesThenFails() {
            // 模拟主 ORM 在同一事务中先写入一条数据
            jdbcTemplate.update("INSERT INTO t_user_account (id, user_name, age) VALUES (?, ?, ?)", 10L, "FromPrimaryOrm", 30);

            // Joya 共享同一条事务连接，能立即读到未提交的数据
            UserAccount seenByJoya = joyaClient.from(UserAccount.class)
                    .where(w -> w.eq(UserAccount::getId, 10L))
                    .one()
                    .orElseThrow();
            assertEquals("FromPrimaryOrm", seenByJoya.getUserName());

            // Joya 在同一事务内再写一条数据
            UserAccount joyaRow = new UserAccount();
            joyaRow.setId(11L);
            joyaRow.setUserName("FromJoya");
            joyaRow.setAge(25);
            joyaClient.of(UserAccount.class).insert(joyaRow);

            // 抛出异常触发 @Transactional 整体回滚
            throw new IllegalStateException("Simulated business failure");
        }

        @Transactional
        public void nestedJoyaTxWithSavepointInsideSpringTransactional(AtomicInteger afterCommitCounter) {
            jdbcTemplate.update("INSERT INTO t_user_account (id, user_name, age) VALUES (?, ?, ?)", 20L, "OuterSpringTx", 40);

            joyaClient.tx(tx -> {
                tx.afterCommit(afterCommitCounter::incrementAndGet);

                // 在嵌套的 joya.tx 中能读到外层 @Transactional 的未提交写入（证明只使用了一条连接）
                assertTrue(tx.of(UserAccount.class).existsById(20L));

                UserAccount innerCommitted = new UserAccount();
                innerCommitted.setId(21L);
                innerCommitted.setUserName("InnerJoyaTx");
                innerCommitted.setAge(28);
                tx.of(UserAccount.class).insert(innerCommitted);

                // 保存点局部回滚：失败不污染外层 @Transactional
                assertThrows(RuntimeException.class, () -> tx.savepoint(sp -> {
                    UserAccount badRow = new UserAccount();
                    badRow.setId(22L);
                    badRow.setUserName("RolledBackBySavepoint");
                    badRow.setAge(99);
                    sp.of(UserAccount.class).insert(badRow);
                    throw new RuntimeException("Trigger savepoint rollback");
                }));

                // 此时外层 @Transactional 尚未提交，afterCommit 还不应触发
                assertEquals(0, afterCommitCounter.get());

                // 在读写 @Transactional 中尝试开启 readOnly=true 的内层 joya.tx 必须快速拒绝
                assertThrows(com.sondertara.joya.core.tx.TxException.class,
                        () -> joyaClient.tx().readOnly().run(roTx -> {}));
            });
        }

        @Transactional
        public void joinedJoyaTxMustNotRetryOnDeadlock(AtomicInteger attempts) {
            joyaClient.tx().retryOnDeadlock(3).run(tx -> {
                attempts.incrementAndGet();
                throw new com.sondertara.joya.exceptions.DbException(
                        new java.sql.SQLException("deadlock inside joined spring tx", "40001"));
            });
        }
    }

    @Autowired
    private JoyaClient joyaClient;

    @Autowired
    private JoyaClientRegistry joyaRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserTxCollaborator txCollaborator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUpSchema() {
        String userTableDdl = """
                CREATE TABLE IF NOT EXISTS t_user_account (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64) NOT NULL,
                    age INT NOT NULL
                )
                """;
        joyaClient.sql(userTableDdl).update();
        joyaClient.of(UserAccount.class).truncate();

        JoyaClient orderClient = joyaRegistry.get("order");
        orderClient.sql(userTableDdl).update();
        orderClient.of(UserAccount.class).truncate();

        orderClient.sql("""
                CREATE TABLE IF NOT EXISTS t_order_record (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL,
                    amount INT NOT NULL
                )
                """).update();
        orderClient.of(OrderRecord.class).truncate();

        TestConfig.GLOBAL_SQL_LOG.clear();
        TestConfig.ORDER_ONLY_SQL_LOG.clear();
    }

    private static ApplicationContextRunner autoDiscoveryRunner() {
        return new ApplicationContextRunner().withUserConfiguration(AutoDiscoveryConfig.class);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Test
    void bootAutoConfigurationDiscovery_loadsJoyaAndAcceptsInheritedDefaults() {
        autoDiscoveryRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:auto_discovery_db;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClientRegistry registry = context.getBean(JoyaClientRegistry.class);
                    assertSame(registry.primary(), context.getBean(JoyaClient.class));
                    assertEquals(Set.of(JoyaClientRegistry.PRIMARY), registry.names());
                    assertEquals(1000, registry.primary().settings().batchSize());
                });
    }

    @Test
    void selfManagedPrimary_rejectsJndiAndXaBeforeCreatingDataSource() {
        TrackedDataSource.reset();
        autoDiscoveryRunner()
                .withPropertyValues(
                        "joya.primary.use-spring=false",
                        "joya.primary.type=" + TrackedDataSource.class.getName(),
                        "joya.primary.url=jdbc:h2:mem:unsupported_primary",
                        "joya.primary.jndi-name=java:comp/env/jdbc/primary",
                        "joya.primary.xa.data-source-class-name=org.example.UnsupportedXaDataSource")
                .run(context -> {
                    Throwable startupFailure = context.getStartupFailure();
                    assertNotNull(startupFailure);
                    String message = rootCause(startupFailure).getMessage();
                    assertTrue(message.contains("joya.primary.jndi-name"), message);
                    assertTrue(message.contains("joya.primary.xa.data-source-class-name"), message);
                    assertTrue(message.contains("not implemented"), message);
                    assertEquals(0, TrackedDataSource.CREATED_COUNT.get());
                });
    }

    @Test
    void selfManagedExtra_rejectsJndiAndXaBeforeCreatingDataSource() {
        TrackedDataSource.reset();
        autoDiscoveryRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:extra_validation_db;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.primary.use-spring=true",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].type=" + TrackedDataSource.class.getName(),
                        "joya.datasources[0].url=jdbc:h2:mem:unsupported_order",
                        "joya.datasources[0].jndi-name=java:comp/env/jdbc/order",
                        "joya.datasources[0].xa.data-source-class-name=org.example.UnsupportedXaDataSource")
                .run(context -> {
                    Throwable startupFailure = context.getStartupFailure();
                    assertNotNull(startupFailure);
                    String message = rootCause(startupFailure).getMessage();
                    assertTrue(message.contains("joya.datasources[0].jndi-name"), message);
                    assertTrue(message.contains("joya.datasources[0].xa.data-source-class-name"), message);
                    assertTrue(message.contains("not implemented"), message);
                    assertEquals(0, TrackedDataSource.CREATED_COUNT.get());
                });
    }

    @Test
    void registryBuildFailure_closesResourcesInReverseOrderAndPreservesAllFailures() {
        TrackedDataSource.reset();
        FailingCustomizerConfig.FAILURE.set(null);
        autoDiscoveryRunner()
                .withUserConfiguration(FailingCustomizerConfig.class)
                .withPropertyValues(
                        "joya.primary.use-spring=false",
                        "joya.primary.type=" + TrackedDataSource.class.getName(),
                        "joya.primary.url=jdbc:h2:mem:cleanup_primary",
                        "joya.primary.username=sa",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].type=" + TrackedDataSource.class.getName(),
                        "joya.datasources[0].url=jdbc:h2:mem:cleanup_order",
                        "joya.datasources[0].username=sa")
                .run(context -> {
                    Throwable startupFailure = context.getStartupFailure();
                    assertNotNull(startupFailure);
                    RuntimeException failure = FailingCustomizerConfig.FAILURE.get();
                    assertNotNull(failure);
                    assertSame(failure, rootCause(startupFailure));
                    assertEquals(2, TrackedDataSource.CREATED_COUNT.get());
                    assertEquals(List.of("order", "primary"), TrackedDataSource.CLOSE_ORDER);
                    assertEquals(2, failure.getSuppressed().length);
                    assertEquals("close-order", failure.getSuppressed()[0].getMessage());
                    assertEquals("close-primary", failure.getSuppressed()[1].getMessage());
                });
    }

    @Test
    void nativeSpringDataSourceUntouched_andJoyaClientRegistryIsHomogeneous() {
        // 1. 验证未污染 Spring 容器的顶层 DataSource Bean（容器中仍然只有 Spring Boot 原生自动装配的 1 个 DataSource）
        assertEquals(1, applicationContext.getBeanNamesForType(DataSource.class).length);

        // 2. 验证主库 JoyaClient 与 JoyaClientRegistry 同构关系：primary() 即 get("primary")
        assertNotNull(joyaClient);
        assertSame(joyaClient, joyaRegistry.primary());
        assertSame(joyaClient, joyaRegistry.get(JoyaClientRegistry.PRIMARY));
        assertEquals(Set.of("primary", "order"), joyaRegistry.names());

        JoyaClient orderClient = joyaRegistry.get("order");
        assertNotNull(orderClient);
        assertNotSame(joyaClient, orderClient);

        // 3. Optional 动态降级与未知名称异常提示
        assertSame(joyaClient, joyaRegistry.find("nonExistentTenant").orElseGet(joyaRegistry::primary));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> joyaRegistry.get("unknown"));
        assertTrue(ex.getMessage().contains("primary"));
        assertTrue(ex.getMessage().contains("order"));

        // 4. 验证顶层配置与 joya.datasources.order.* 参数覆写生效
        assertEquals(2048, joyaClient.settings().batchSize());
        assertEquals(512, orderClient.settings().batchSize());
        assertEquals(15, orderClient.settings().queryTimeoutSeconds());

        // 5. 验证 JoyaClientCustomizer 收到同构的客户端名称回调（"primary" 与 "order"）
        assertEquals(List.of("primary", "order"), TestConfig.CUSTOMIZER_INVOKED_FOR);
    }

    @Test
    void interceptors_scopedPerClient_andJakartaPersistenceEntitiesWork() {
        UserAccount u = new UserAccount();
        u.setId(1L);
        u.setUserName("Alice");
        u.setAge(26);
        u.setEphemeralNote("ignored-by-jakarta-transient");
        joyaClient.of(UserAccount.class).insert(u);

        assertEquals(1, TestConfig.GLOBAL_SQL_LOG.size());
        assertEquals(0, TestConfig.ORDER_ONLY_SQL_LOG.size());

        OrderRecord o = new OrderRecord();
        o.setId(100L);
        o.setOrderNo("ORD-2026-001");
        o.setAmount(9900);
        joyaRegistry.get("order").of(OrderRecord.class).insert(o);

        assertEquals(2, TestConfig.GLOBAL_SQL_LOG.size());
        assertEquals(1, TestConfig.ORDER_ONLY_SQL_LOG.size());
    }

    @Test
    void sameEntityAndSameMapperInterface_workAcrossMultipleDataSources() {
        JoyaClient orderClient = joyaRegistry.get("order");

        // 在主库（primary）写入 Alice (age=20) 和 Bob (age=35)
        UserAccount u1 = new UserAccount();
        u1.setId(1L);
        u1.setUserName("Alice");
        u1.setAge(20);
        UserAccount u2 = new UserAccount();
        u2.setId(2L);
        u2.setUserName("Bob");
        u2.setAge(35);
        joyaClient.of(UserAccount.class).insertAll(List.of(u1, u2));

        // 在扩展库（order）写入同名同结构的 UserAccount：Alice (age=99)
        UserAccount archivedAlice = new UserAccount();
        archivedAlice.setId(1L);
        archivedAlice.setUserName("Alice");
        archivedAlice.setAge(99);
        orderClient.of(UserAccount.class).insert(archivedAlice);

        // 同一个 UserAccountMapper 接口，分别在主库与扩展库上调用：代理实例按库缓存，数据完全隔离
        UserAccountMapper mainUserMapper = joyaClient.mapper(UserAccountMapper.class);
        UserAccountMapper orderDbUserMapper = orderClient.mapper(UserAccountMapper.class);
        assertSame(mainUserMapper, joyaRegistry.primary().mapper(UserAccountMapper.class));
        assertNotSame(mainUserMapper, orderDbUserMapper);

        assertEquals(20, mainUserMapper.findByUserName("Alice").orElseThrow().getAge());
        assertEquals(99, orderDbUserMapper.findByUserName("Alice").orElseThrow().getAge());
        assertEquals(1L, mainUserMapper.countByAgeGreaterThan(25));
        assertEquals(1L, orderDbUserMapper.countByAgeGreaterThan(80));
        assertEquals("Alice", mainUserMapper.selectByRawName("Alice").orElseThrow().getUserName());

        // 显式事务 Tx 内直接调用 tx.mapper(...)
        joyaClient.tx(tx -> {
            UserAccount u3 = new UserAccount();
            u3.setId(3L);
            u3.setUserName("Charlie");
            u3.setAge(40);
            tx.mapper(UserAccountMapper.class).save(u3);
            assertEquals(3, tx.mapper(UserAccountMapper.class).findByAgeGreaterThanEqual(20).size());
        });

        // OrderRecordMapper 同样直接通过 client.mapper(...) 获取
        OrderRecord o1 = new OrderRecord();
        o1.setId(501L);
        o1.setOrderNo("ORD-501");
        o1.setAmount(1200);
        orderClient.mapper(OrderRecordMapper.class).save(o1);
        assertEquals(1200, orderClient.mapper(OrderRecordMapper.class).findByOrderNo("ORD-501").orElseThrow().getAmount());
    }

    @Test
    void springTransactional_sharesConnectionWithPrimaryOrm_andRollsBackTogether() {
        assertThrows(IllegalStateException.class,
                () -> txCollaborator.primaryOrmWritesThenJoyaReadsAndWritesThenFails());

        // 事务回滚后，主 ORM 写入的 id=10 与 Joya 写入的 id=11 均已回滚
        assertEquals(0L, joyaClient.of(UserAccount.class).count());
    }

    @Test
    void joyaTxNestedInsideSpringTransactional_reusesSingleConnection_andSupportsSavepoint() {
        AtomicInteger afterCommitCounter = new AtomicInteger(0);
        txCollaborator.nestedJoyaTxWithSavepointInsideSpringTransactional(afterCommitCounter);

        // 外层 @Transactional 提交后，afterCommit 恰好执行一次
        assertEquals(1, afterCommitCounter.get());

        // id=20 (外层) 与 id=21 (内层 joya.tx) 提交成功；id=22 (savepoint 回滚) 不存在
        assertTrue(joyaClient.of(UserAccount.class).existsById(20L));
        assertTrue(joyaClient.of(UserAccount.class).existsById(21L));
        assertFalse(joyaClient.of(UserAccount.class).existsById(22L));

        // 加入外层 @Transactional 的内层 joya.tx 回滚仅设置 rollback-only，未经物理回滚确认严禁触发死锁重试
        AtomicInteger joinedRetryAttempts = new AtomicInteger(0);
        assertThrows(com.sondertara.joya.exceptions.DbException.class,
                () -> txCollaborator.joinedJoyaTxMustNotRetryOnDeadlock(joinedRetryAttempts));
        assertEquals(1, joinedRetryAttempts.get(), "加入外层 Spring 事务的内层 joya.tx 严禁自动重试");
    }

    @Test
    void multiDataSourceTransactions_areCompletelyIsolated() {
        JoyaClient orderClient = joyaRegistry.get("order");
        TransactionTemplate userTxTemplate = new TransactionTemplate(transactionManager);
        userTxTemplate.executeWithoutResult(status -> {
            UserAccount u = new UserAccount();
            u.setId(99L);
            u.setUserName("CommittedUser");
            u.setAge(30);
            joyaClient.of(UserAccount.class).insert(u);

            // 在主库事务内，order 扩展库事务回滚不影响主库
            assertThrows(RuntimeException.class, () -> orderClient.tx(orderTx -> {
                OrderRecord o = new OrderRecord();
                o.setId(999L);
                o.setOrderNo("ORD-ROLLBACK");
                o.setAmount(500);
                orderTx.of(OrderRecord.class).insert(o);
                throw new RuntimeException("Order DB rollback");
            }));
        });

        assertTrue(joyaClient.of(UserAccount.class).existsById(99L));
        assertFalse(orderClient.of(OrderRecord.class).existsById(999L));
    }
}
