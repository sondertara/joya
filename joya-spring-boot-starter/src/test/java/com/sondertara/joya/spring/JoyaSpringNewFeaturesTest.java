package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.JdbcExecutor;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.naming.TableNameResolver;
import com.sondertara.joya.spring.autoconfigure.JoyaClientCustomizer;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.spring.support.SpringSqlExceptionTranslator;
import com.sondertara.joya.spring.test.order.OrderRecord;
import com.sondertara.joya.spring.test.user.UserAccount;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot 起步依赖新特性集成测试：
 * <ul>
 *   <li>默认 {@link SpringSqlExceptionTranslator}（惰性连接探测 + {@code DataAccessException} + 死锁重试兼容）；</li>
 *   <li>{@code tx().requiresNew()} 挂起外层 {@code @Transactional} 执行独立事务；</li>
 *   <li>{@link TableNameResolver} / {@link Executor.Factory} 自动发现与 {@code @Qualifier} 路由；</li>
 *   <li>{@code data-source-bean} / {@code transaction-manager-bean} 容器复用与命名 {@code JoyaClient} 注入（含 Bean 重名避让）；</li>
 *   <li>{@code JoyaActuatorAutoConfiguration} 旁路健康检查及开关/缺省依赖条件。</li>
 * </ul>
 */
class JoyaSpringNewFeaturesTest {

    interface CloseableDataSource extends DataSource, AutoCloseable {
    }

    private static CloseableDataSource trackedH2(String url, AtomicInteger closeCount) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(url);
        h2.setUser("sa");
        h2.setPassword("");
        return (CloseableDataSource) Proxy.newProxyInstance(
                CloseableDataSource.class.getClassLoader(),
                new Class<?>[]{CloseableDataSource.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closeCount.incrementAndGet();
                        return null;
                    }
                    try {
                        return method.invoke(h2, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getTargetException();
                    }
                });
    }

    // ===== 1. Default SpringSqlExceptionTranslator + Deadlock Retry + Lazy Startup =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LazyDataSourceStartupConfig {
        static final AtomicInteger GET_CONNECTION_CALLS = new AtomicInteger();

        @Bean
        DataSource dataSource() {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getConnection".equals(name)) {
                            GET_CONNECTION_CALLS.incrementAndGet();
                            throw new SQLException("No lazy violation allowed on startup", "08001");
                        }
                        if ("isWrapperFor".equals(name)) {
                            return false;
                        }
                        if ("unwrap".equals(name)) {
                            throw new SQLException("Not a wrapper");
                        }
                        if ("getLoginTimeout".equals(name)) {
                            return 0;
                        }
                        if ("toString".equals(name)) {
                            return "LazySpyDataSource";
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        return null;
                    });
        }
    }

    @Test
    void defaultSpringSqlExceptionTranslator_isLazyOnStartup_translatesDuplicateAndBadSql_andSupportsDeadlockRetry() {
        LazyDataSourceStartupConfig.GET_CONNECTION_CALLS.set(0);
        new ApplicationContextRunner()
                .withUserConfiguration(LazyDataSourceStartupConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(JoyaClient.class));
                    assertEquals(0, LazyDataSourceStartupConfig.GET_CONNECTION_CALLS.get(),
                            "启动阶段不得提前打开 JDBC 连接探测 SQLErrorCodes");
                });

        AtomicInteger attempts = new AtomicInteger();
        new ApplicationContextRunner()
                .withUserConfiguration(LazyStartupTranslatorTestConfig.class)
                .withBean("deadlockFailOnceExecutorFactory", Executor.Factory.class, () ->
                        (connections, translator) -> new JdbcExecutor(connections, translator) {
                            @Override
                            public int update(SqlStatement statement) {
                                if (statement.sql().contains("TRIGGER_DEADLOCK") && attempts.incrementAndGet() == 1) {
                                    throw translator.translate("update", statement,
                                            new SQLException("Simulated deadlock", "40001"));
                                }
                                return super.update(statement);
                            }
                        })
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:spring_ex_trans;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClient joya = context.getBean(JoyaClient.class);
                    joya.sql("DROP TABLE IF EXISTS t_user_account").update();
                    joya.sql("CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT)").update();

                    UserAccount u1 = new UserAccount();
                    u1.setId(1L);
                    u1.setUserName("Alice");
                    u1.setAge(20);
                    joya.of(UserAccount.class).insert(u1);

                    // 主键冲突 -> Spring DuplicateKeyException
                    Throwable dup = assertThrows(DataAccessException.class, () -> joya.of(UserAccount.class).insert(u1));
                    assertInstanceOf(DuplicateKeyException.class, dup);

                    // 语法/缺表错误 -> Spring BadSqlGrammarException
                    Throwable badSql = assertThrows(DataAccessException.class,
                            () -> joya.sql("SELECT * FROM missing_table_xyz").listMap());
                    assertInstanceOf(BadSqlGrammarException.class, badSql);

                    // 经 SpringSqlExceptionTranslator 包装后仍保留 SQLException(40001)，retryOnDeadlock 成功重试提交
                    attempts.set(0);
                    String res = joya.tx().retryOnDeadlock(3).call(tx -> {
                        tx.sql("INSERT INTO t_user_account (id, user_name, age) VALUES (2, 'TRIGGER_DEADLOCK', 30)")
                                .update();
                        return "ok";
                    });
                    assertEquals("ok", res);
                    assertEquals(2, attempts.get());
                    assertTrue(joya.of(UserAccount.class).existsById(2L));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LazyStartupTranslatorTestConfig {
    }

    // ===== 2. SpringTxEngine + requiresNew() =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class RequiresNewConfig {
        @Bean
        RequiresNewService requiresNewService(JoyaClient joyaClient) {
            return new RequiresNewService(joyaClient);
        }
    }

    static class RequiresNewService {
        private final JoyaClient joya;

        RequiresNewService(JoyaClient joya) {
            this.joya = joya;
        }

        @Transactional
        public void outerRollsBackAfterInnerRequiresNewCommits() {
            joya.sql("INSERT INTO t_user_account (id, user_name, age) VALUES (1, 'outer-rollback', 10)").update();
            joya.tx().requiresNew().run(innerTx ->
                    innerTx.sql("INSERT INTO t_user_account (id, user_name, age) VALUES (2, 'inner-committed', 20)").update());
            throw new IllegalStateException("force outer rollback");
        }

        @Transactional
        public void outerCommitsEvenWhenInnerRequiresNewRollsBack() {
            joya.sql("INSERT INTO t_user_account (id, user_name, age) VALUES (3, 'outer-committed', 30)").update();
            assertThrows(IllegalArgumentException.class, () -> joya.tx().requiresNew().run(innerTx -> {
                innerTx.sql("INSERT INTO t_user_account (id, user_name, age) VALUES (4, 'inner-rollback', 40)").update();
                throw new IllegalArgumentException("inner fails independently");
            }));
        }
    }

    @Test
    void requiresNew_suspendsOuterSpringTransactional_andCommitsOrRollsBackIndependently() {
        new ApplicationContextRunner()
                .withUserConfiguration(RequiresNewConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:spring_requires_new;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClient joya = context.getBean(JoyaClient.class);
                    joya.sql("DROP TABLE IF EXISTS t_user_account").update();
                    joya.sql("CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT)").update();

                    RequiresNewService service = context.getBean(RequiresNewService.class);
                    assertThrows(IllegalStateException.class, service::outerRollsBackAfterInnerRequiresNewCommits);
                    assertFalse(joya.of(UserAccount.class).existsById(1L), "外层 @Transactional 应回滚");
                    assertTrue(joya.of(UserAccount.class).existsById(2L), "requiresNew 内层事务必须已独立提交");

                    service.outerCommitsEvenWhenInnerRequiresNewRollsBack();
                    assertTrue(joya.of(UserAccount.class).existsById(3L), "内层 requiresNew 回滚不得污染外层事务提交");
                    assertFalse(joya.of(UserAccount.class).existsById(4L), "内层 requiresNew 自身修改应已回滚");
                });
    }

    // ===== 3. TableNameResolver & Executor.Factory auto-discovery =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TableAndExecutorExtensionConfig {
        static final AtomicInteger GLOBAL_EXEC_CALLS = new AtomicInteger();
        static final AtomicInteger ORDER_EXEC_CALLS = new AtomicInteger();

        @Bean
        TableNameResolver defaultTableResolver() {
            return TableNameResolver.IDENTITY;
        }

        @Bean
        @Qualifier("order")
        TableNameResolver orderTableResolver() {
            return (schema, table) -> "t_order_record".equals(table)
                    ? "t_order_record_v2"
                    : table;
        }

        @Bean
        Executor.Factory globalExecutorFactory() {
            return (connections, translator) -> {
                GLOBAL_EXEC_CALLS.incrementAndGet();
                return new JdbcExecutor(connections, translator);
            };
        }

        @Bean
        @Qualifier("order")
        Executor.Factory orderExecutorFactory() {
            return (connections, translator) -> {
                ORDER_EXEC_CALLS.incrementAndGet();
                return new JdbcExecutor(connections, translator);
            };
        }
    }

    @Test
    void tableNameResolverAndExecutorFactory_areAutoDiscoveredAndRoutedPerClient() {
        TableAndExecutorExtensionConfig.GLOBAL_EXEC_CALLS.set(0);
        TableAndExecutorExtensionConfig.ORDER_EXEC_CALLS.set(0);
        new ApplicationContextRunner()
                .withUserConfiguration(TableAndExecutorExtensionConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:ext_tbl_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:ext_tbl_order;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].password=",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClientRegistry registry = context.getBean(JoyaClientRegistry.class);
                    JoyaClient primary = registry.primary();
                    JoyaClient order = registry.get("order");

                    assertTrue(TableAndExecutorExtensionConfig.GLOBAL_EXEC_CALLS.get() >= 1);
                    assertTrue(TableAndExecutorExtensionConfig.ORDER_EXEC_CALLS.get() >= 1);

                    primary.sql("DROP TABLE IF EXISTS t_user_account").update();
                    primary.sql("CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT)").update();
                    UserAccount u = new UserAccount();
                    u.setId(1L);
                    u.setUserName("P");
                    u.setAge(10);
                    primary.of(UserAccount.class).insert(u);
                    assertEquals(1L, primary.from(UserAccount.class).count());

                    order.sql("DROP TABLE IF EXISTS t_order_record_v2").update();
                    order.sql("CREATE TABLE t_order_record_v2 (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT)").update();
                    OrderRecord o = new OrderRecord();
                    o.setId(10L);
                    o.setOrderNo("ORD-10");
                    o.setAmount(99);
                    order.of(OrderRecord.class).insert(o);
                    assertEquals("ORD-10", order.from(OrderRecord.class).one().orElseThrow().getOrderNo());
                });
    }

    // ===== 4. Container DataSource/TxManager reuse + @Qualifier("<name>") JoyaClient =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class ContainerBeanReuseConfig {
        static final AtomicInteger PRIMARY_CLOSE_COUNT = new AtomicInteger();
        static final AtomicInteger ORDER_CLOSE_COUNT = new AtomicInteger();

        @Primary
        @Bean(name = "primaryDs")
        DataSource primaryDs() {
            return trackedH2("jdbc:h2:mem:reuse_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1", PRIMARY_CLOSE_COUNT);
        }

        @Bean(name = "primaryTxManager")
        DataSourceTransactionManager primaryTxManager(@Qualifier("primaryDs") DataSource primaryDs) {
            return new DataSourceTransactionManager(primaryDs);
        }

        /** Bean 名刻意与扩展数据源 name=\"order\" 重名，验证命名 JoyaClient 自动避让并保留 @Qualifier(\"order\")。 */
        @Bean(name = "order")
        DataSource orderDataSource() {
            return trackedH2("jdbc:h2:mem:reuse_order;MODE=MYSQL;DB_CLOSE_DELAY=-1", ORDER_CLOSE_COUNT);
        }

        @Bean(name = "orderTxManager")
        DataSourceTransactionManager orderTxManager(@Qualifier("order") DataSource orderDs) {
            return new DataSourceTransactionManager(orderDs);
        }

        @Bean
        OrderConsumer orderConsumer(JoyaClient primaryClient,
                                    @Qualifier("order") JoyaClient orderClient) {
            return new OrderConsumer(primaryClient, orderClient);
        }
    }

    static class OrderConsumer {
        private final JoyaClient primaryClient;
        private final JoyaClient orderClient;

        OrderConsumer(JoyaClient primaryClient, JoyaClient orderClient) {
            this.primaryClient = primaryClient;
            this.orderClient = orderClient;
        }

        public JoyaClient primaryClient() {
            return primaryClient;
        }

        public JoyaClient orderClient() {
            return orderClient;
        }

        @Transactional("orderTxManager")
        public void writeOrderAndFail(OrderRecord record) {
            orderClient.of(OrderRecord.class).insert(record);
            throw new IllegalStateException("rollback orderTxManager");
        }
    }

    @Test
    void containerBeanReuse_injectsNamedQualifierClientWithoutNameCollision_sharesTxManager_andDoesNotCloseContainerDataSource() {
        ContainerBeanReuseConfig.PRIMARY_CLOSE_COUNT.set(0);
        ContainerBeanReuseConfig.ORDER_CLOSE_COUNT.set(0);

        new ApplicationContextRunner()
                .withUserConfiguration(ContainerBeanReuseConfig.class)
                .withPropertyValues(
                        "joya.primary.data-source-bean=primaryDs",
                        "joya.primary.transaction-manager-bean=primaryTxManager",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].data-source-bean=order",
                        "joya.datasources[0].transaction-manager-bean=orderTxManager")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    OrderConsumer consumer = context.getBean(OrderConsumer.class);
                    JoyaClientRegistry registry = context.getBean(JoyaClientRegistry.class);
                    assertSame(registry.primary(), consumer.primaryClient());
                    assertSame(registry.get("order"), consumer.orderClient());
                    assertNotSame(consumer.primaryClient(), consumer.orderClient());

                    // 验证 @Bean("order") DataSource 未被覆盖，命名 client 避让为 orderJoyaClient 且带 @Qualifier("order") 与 dependsOn
                    assertInstanceOf(DataSource.class, context.getBean("order"));
                    assertSame(consumer.orderClient(), context.getBean("orderJoyaClient", JoyaClient.class));
                    BeanDefinition orderClientBd = context.getBeanFactory().getBeanDefinition("orderJoyaClient");
                    assertNotNull(orderClientBd.getDependsOn());
                    assertTrue(Arrays.asList(orderClientBd.getDependsOn()).contains("joyaClientRegistry"),
                            "命名 JoyaClient 应显式 dependsOn joyaClientRegistry 以保障停机逆序销毁");

                    consumer.orderClient().sql("DROP TABLE IF EXISTS t_order_record").update();
                    consumer.orderClient().sql("CREATE TABLE t_order_record (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT)").update();

                    // @Transactional("orderTxManager") 必须与 orderClient 共享连接并一同回滚
                    OrderRecord r = new OrderRecord();
                    r.setId(100L);
                    r.setOrderNo("ORD-ROLLBACK");
                    r.setAmount(50);
                    assertThrows(IllegalStateException.class, () -> consumer.writeOrderAndFail(r));
                    assertEquals(0L, consumer.orderClient().from(OrderRecord.class).count());

                    // 调用 registry.close() 不得关闭容器管理的 DataSource Bean
                    registry.close();
                    assertEquals(0, ContainerBeanReuseConfig.PRIMARY_CLOSE_COUNT.get(),
                            "容器托管主库 DataSource 不得由 JoyaClientRegistry 关闭");
                    assertEquals(0, ContainerBeanReuseConfig.ORDER_CLOSE_COUNT.get(),
                            "data-source-bean 复用的扩展库 DataSource 不得由 JoyaClientRegistry 关闭");
                });

        // 冲突配置（data-source-bean + url）快速失败
        new ApplicationContextRunner()
                .withUserConfiguration(ContainerBeanReuseConfig.class)
                .withPropertyValues(
                        "joya.primary.data-source-bean=primaryDs",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].data-source-bean=order",
                        "joya.datasources[0].url=jdbc:h2:mem:conflict")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(rootMessage(failure).contains("data-source-bean"), rootMessage(failure));
                });
    }

    // ===== 5. Actuator HealthIndicator =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ActuatorTestConfig {
    }

    @Test
    void actuatorAutoConfiguration_reportsSidecarCompositeHealth_respectsDisableToggle_andSkipsWhenActuatorAbsent() {
        // 1. 存在自建旁路数据源 -> CompositeHealthContributor
        new ApplicationContextRunner()
                .withUserConfiguration(ActuatorTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:act_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:act_order;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].password=",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    HealthContributor contributor = context.getBean("joyaHealthContributor", HealthContributor.class);
                    CompositeHealthContributor composite = assertInstanceOf(CompositeHealthContributor.class, contributor);
                    HealthIndicator orderHealth = assertInstanceOf(HealthIndicator.class, composite.getContributor("order"));
                    assertEquals(Status.UP, orderHealth.health().getStatus());
                });

        // 2. 仅复用 Spring 主库（无自建旁路库） -> 轻量级 HealthIndicator
        new ApplicationContextRunner()
                .withUserConfiguration(ActuatorTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:act_only_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    HealthIndicator indicator = context.getBean("joyaHealthContributor", HealthIndicator.class);
                    var health = indicator.health();
                    assertEquals(Status.UP, health.getStatus());
                    assertEquals(0, health.getDetails().get("sidecarDataSources"));
                });

        // 3. management.health.joya.enabled=false -> 不注册 joyaHealthContributor
        new ApplicationContextRunner()
                .withUserConfiguration(ActuatorTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:act_disabled;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "management.health.joya.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("joyaHealthContributor"));
                });

        // 4. 缺省 spring-boot-actuator 依赖 -> 安静跳过，主功能正常
        new ApplicationContextRunner()
                .withUserConfiguration(ActuatorTestConfig.class)
                .withClassLoader(new FilteredClassLoader(HealthContributor.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:act_missing;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(JoyaClient.class));
                    assertFalse(context.containsBean("joyaHealthContributor"));
                });
    }

    // ===== 6. Review Regressions: Outer @Transactional timeout clamping, Customizer timeout, Slow-SQL standalone, Qualifier backoff =====

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class TimeoutSpyConfig {
        static final List<Integer> RECORDED_TIMEOUTS = new ArrayList<>();

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:spring_timeout_clamp;MODE=MYSQL;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        Object res = invokeUnwrapped(method, h2, args);
                        if ("getConnection".equals(method.getName()) && res instanceof Connection conn) {
                            return wrapTimeoutConnection(conn);
                        }
                        return res;
                    });
        }

        @Bean
        OuterTimeoutService outerTimeoutService(JoyaClient joyaClient) {
            return new OuterTimeoutService(joyaClient);
        }

        private static Connection wrapTimeoutConnection(Connection target) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object res = invokeUnwrapped(method, target, args);
                        if ("prepareStatement".equals(method.getName()) && res instanceof PreparedStatement ps) {
                            return (PreparedStatement) Proxy.newProxyInstance(
                                    PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{PreparedStatement.class},
                                    (psProxy, psMethod, psArgs) -> {
                                        if ("setQueryTimeout".equals(psMethod.getName()) && psArgs != null && psArgs.length == 1) {
                                            RECORDED_TIMEOUTS.add((Integer) psArgs[0]);
                                        }
                                        return invokeUnwrapped(psMethod, ps, psArgs);
                                    });
                        }
                        return res;
                    });
        }

        private static Object invokeUnwrapped(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        }
    }

    static class OuterTimeoutService {
        private final JoyaClient joya;

        OuterTimeoutService(JoyaClient joya) {
            this.joya = joya;
        }

        @Transactional(timeout = 4)
        public void verifyJoinedTxClampsToOuterSpringDeadline() {
            joya.tx(tx -> {
                TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                tx.withSettings(s -> s.withQueryTimeoutSeconds(30)).sql("SELECT 1").value(Integer.class);
                assertEquals(1, TimeoutSpyConfig.RECORDED_TIMEOUTS.size());
                assertTrue(TimeoutSpyConfig.RECORDED_TIMEOUTS.get(0) <= 4,
                        "加入外层 @Transactional(timeout=4) 时，tx.withSettings(30) 必须被钳位到 <= 4 秒");

                TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                tx.withSettings(s -> s.withQueryTimeoutSeconds(0)).sql("SELECT 1").value(Integer.class);
                assertEquals(1, TimeoutSpyConfig.RECORDED_TIMEOUTS.size());
                assertTrue(TimeoutSpyConfig.RECORDED_TIMEOUTS.get(0) <= 4,
                        "加入外层 @Transactional(timeout=4) 时，tx.withSettings(0) 不得绕过外层事务截止时间");

                TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                tx.withSettings(s -> s.withQueryTimeoutSeconds(2)).sql("SELECT 1").value(Integer.class);
                assertEquals(List.of(2), TimeoutSpyConfig.RECORDED_TIMEOUTS);
            });

            joya.tx().timeoutSeconds(25).run(tx -> {
                TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                tx.sql("SELECT 1").value(Integer.class);
                assertEquals(1, TimeoutSpyConfig.RECORDED_TIMEOUTS.size());
                assertTrue(TimeoutSpyConfig.RECORDED_TIMEOUTS.get(0) <= 4,
                        "加入外层 @Transactional(timeout=4) 时，内层 timeoutSeconds(25) 同样受外层 4 秒截止预算约束");
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CustomQualifiedOrderClientConfig {
        @Bean
        @Qualifier("order")
        JoyaClient customOrderClient(JoyaClientRegistry registry) {
            return registry.get("order").withSettings(s -> s.withBatchSize(77));
        }

        @Bean
        OrderQualifierHolder orderQualifierHolder(JoyaClient primaryClient,
                                                  @Qualifier("order") JoyaClient orderClient) {
            return new OrderQualifierHolder(primaryClient, orderClient);
        }
    }

    record OrderQualifierHolder(JoyaClient primaryClient, JoyaClient orderClient) {
    }

    @Test
    void outerSpringDeadlineClamping_customizerTimeout_standaloneSlowLog_andUserQualifiedClientBackoff() {
        // 1 & 2. JoyaClientCustomizer 修改 queryTimeoutSeconds 在 SpringConnectionProvider 模式下生效 + 外层 @Transactional(timeout=4) 钳位
        new ApplicationContextRunner()
                .withUserConfiguration(TimeoutSpyConfig.class)
                .withBean(JoyaClientCustomizer.class, () -> (name, ds, builder) -> builder.queryTimeoutSeconds(7))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClient joya = context.getBean(JoyaClient.class);
                    assertEquals(7, joya.settings().executionOptions().queryTimeoutSeconds());

                    TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                    joya.sql("SELECT 1").value(Integer.class);
                    assertEquals(List.of(7), TimeoutSpyConfig.RECORDED_TIMEOUTS,
                            "SpringConnectionProvider 必须应用 JoyaClientCustomizer 设置的 queryTimeoutSeconds(7)");

                    context.getBean(OuterTimeoutService.class).verifyJoinedTxClampsToOuterSpringDeadline();
                });

        // JoyaClientCustomizer 将 YAML 中的超时清零（15 -> 0）同样生效
        new ApplicationContextRunner()
                .withUserConfiguration(TimeoutSpyConfig.class)
                .withPropertyValues("joya.query-timeout-seconds=15")
                .withBean(JoyaClientCustomizer.class, () -> (name, ds, builder) -> builder.queryTimeoutSeconds(0))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClient joya = context.getBean(JoyaClient.class);
                    TimeoutSpyConfig.RECORDED_TIMEOUTS.clear();
                    joya.sql("SELECT 1").value(Integer.class);
                    assertTrue(TimeoutSpyConfig.RECORDED_TIMEOUTS.isEmpty(),
                            "JoyaClientCustomizer 显式设 queryTimeoutSeconds(0) 应清除 YAML 默认超时");
                });

        // 3. 生产环境单独配置 joya.slow-sql-threshold-ms（不开启 joya.sql-log=true）同样自动注册 joyaSqlLogListener
        new ApplicationContextRunner()
                .withUserConfiguration(ActuatorTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:slow_only_auto;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.slow-sql-threshold-ms=200")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("joyaSqlLogListener"),
                            "单独配置 joya.slow-sql-threshold-ms>0 时必须自动注册 joyaSqlLogListener");
                });

        // 4. 用户自定义 @Bean @Qualifier("order") JoyaClient 时自动退让，不重复注册 orderJoyaClient 造成冲突
        new ApplicationContextRunner()
                .withUserConfiguration(CustomQualifiedOrderClientConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:custom_qual_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:custom_qual_order;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].password=",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JoyaClientRegistry registry = context.getBean(JoyaClientRegistry.class);
                    OrderQualifierHolder holder = context.getBean(OrderQualifierHolder.class);
                    assertSame(registry.primary(), holder.primaryClient(),
                            "仅自定义带 @Qualifier 的副库客户端时，默认 @Primary 主库客户端不得退让或串库");
                    assertEquals(77, holder.orderClient().settings().executionOptions().batchSize());
                    assertFalse(context.containsBean("orderJoyaClient"),
                            "已存在用户定义的 @Qualifier(\"order\") JoyaClient 时应主动退让");
                });
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? "" : cursor.getMessage();
    }
}
