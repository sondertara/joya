package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.spring.autoconfigure.JoyaActuatorAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaClientCustomizer;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.spring.autoconfigure.JoyaNamedClientsAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaObservationAutoConfiguration;
import com.sondertara.joya.spring.support.JoyaObservationExecutionListener;
import com.sondertara.joya.spring.test.order.OrderRecord;
import com.sondertara.joya.spring.test.user.UserAccount;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主 ORM 级 Spring Boot 3 集成专项测试：
 * <ul>
 *   <li>自建主库（{@code joya.primary.use-spring=false}）自动暴露主 {@code transactionManager} 并触发 {@code @Transactional}；</li>
 *   <li>扩展库 {@code expose-transaction-manager=true} 暴露命名事务管理器同时把默认主 TM 提升为 {@code @Primary}，保护无限定 {@code @Transactional}；</li>
 *   <li>自建 {@code HikariDataSource} 自动绑定 Micrometer 连接池指标；</li>
 *   <li>{@link JoyaObservationAutoConfiguration} 完整覆盖普通查询、流式查询关闭收口与异常观测。</li>
 * </ul>
 */
class JoyaPrimaryOrmStarterIntegrationTest {

    static class PrimaryTxService {
        private final JoyaClient primary;

        PrimaryTxService(JoyaClient primary) {
            this.primary = primary;
        }

        @Transactional
        public void insertAndFail(long id) {
            UserAccount user = new UserAccount();
            user.setId(id);
            user.setUserName("rollback-" + id);
            user.setAge(20);
            primary.of(UserAccount.class).insert(user);
            throw new IllegalStateException("force primary rollback");
        }

        @Transactional
        public void insertAndCommit(long id) {
            UserAccount user = new UserAccount();
            user.setId(id);
            user.setUserName("commit-" + id);
            user.setAge(25);
            primary.of(UserAccount.class).insert(user);
        }
    }

    static class MultiTxService {
        private final JoyaClient primary;
        private final JoyaClient order;

        MultiTxService(JoyaClient primary, @Qualifier("order") JoyaClient order) {
            this.primary = primary;
            this.order = order;
        }

        @Transactional
        public void writePrimaryAndFail(long id) {
            UserAccount user = new UserAccount();
            user.setId(id);
            user.setUserName("p-" + id);
            user.setAge(18);
            primary.of(UserAccount.class).insert(user);
            throw new IllegalStateException("rollback primary");
        }

        @Transactional("orderTransactionManager")
        public void writeOrderAndFail(long id) {
            OrderRecord rec = new OrderRecord();
            rec.setId(id);
            rec.setOrderNo("ORD-" + id);
            rec.setAmount(88);
            order.of(OrderRecord.class).insert(rec);
            throw new IllegalStateException("rollback order");
        }

        @Transactional("orderTransactionManager")
        public void writeOrderAndCommit(long id) {
            OrderRecord rec = new OrderRecord();
            rec.setId(id);
            rec.setOrderNo("ORD-" + id);
            rec.setAmount(99);
            order.of(OrderRecord.class).insert(rec);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryTxServiceConfig {
        @Bean
        PrimaryTxService primaryTxService(JoyaClient joyaClient) {
            return new PrimaryTxService(joyaClient);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MultiTxServiceConfig {
        @Bean
        MultiTxService multiTxService(JoyaClient primary, @Qualifier("order") JoyaClient order) {
            return new MultiTxService(primary, order);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EarlyConnectCustomizerConfig {
        @Bean
        JoyaClientCustomizer earlyProbeCustomizer() {
            return (clientName, dataSource, builder) -> {
                if ("earlydb".equals(clientName) && dataSource instanceof HikariDataSource hikari) {
                    // 在 SmartInitializingSingleton 之前提前建连触发池初始化，验证默认 poolName 与指标绑定均不丢失
                    try (var conn = hikari.getConnection()) {
                        assertEquals("joya-earlydb", hikari.getPoolName(),
                                "自建 HikariDataSource 在首次建连前就必须确立 joya-<name> 池名");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
    }

    static class SidecarOnlyTxService {
        private final JoyaClient order;

        SidecarOnlyTxService(@Qualifier("order") JoyaClient order) {
            this.order = order;
        }

        @Transactional("orderTransactionManager")
        public void insertAndFail(long id) {
            OrderRecord rec = new OrderRecord();
            rec.setId(id);
            rec.setOrderNo("ONLY-" + id);
            rec.setAmount(77);
            order.of(OrderRecord.class).insert(rec);
            throw new IllegalStateException("rollback sidecar-only tx");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SidecarOnlyTxServiceConfig {
        @Bean
        SidecarOnlyTxService sidecarOnlyTxService(@Qualifier("order") JoyaClient order) {
            return new SidecarOnlyTxService(order);
        }
    }

    static class RecordingObservationHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> started = new CopyOnWriteArrayList<>();
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return JoyaObservationExecutionListener.OBSERVATION_NAME.equals(context.getName());
        }

        @Override
        public void onStart(Observation.Context context) {
            started.add(context);
        }

        @Override
        public void onError(Observation.Context context) {
            if (context.getError() != null) {
                errors.add(context.getError());
            }
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservationTestConfig {
        @Bean
        RecordingObservationHandler recordingHandler() {
            return new RecordingObservationHandler();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingObservationHandler handler) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(handler);
            return registry;
        }
    }

    @Test
    void selfManagedPrimary_autoExposesTransactionManagerAndEnablesDeclarativeTransactional() {
        String db = "self_primary_tx_" + UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JoyaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class))
                .withUserConfiguration(PrimaryTxServiceConfig.class)
                .withPropertyValues(
                        "joya.primary.use-spring=false",
                        "joya.primary.url=jdbc:h2:mem:" + db + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.primary.username=sa",
                        "joya.primary.password=")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    assertNotNull(ctx.getBean("transactionManager", PlatformTransactionManager.class));
                    JoyaClient client = ctx.getBean(JoyaClient.class);
                    client.sql("CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT)").update();

                    PrimaryTxService service = ctx.getBean(PrimaryTxService.class);
                    assertThrows(IllegalStateException.class, () -> service.insertAndFail(1L));
                    assertEquals(0L, client.of(UserAccount.class).count(),
                            "自建主库 @Transactional 异常必须回滚 Joya 写入");

                    service.insertAndCommit(2L);
                    assertEquals(1L, client.of(UserAccount.class).count());
                });

        // 显式关闭 expose-transaction-manager=false 时不暴露 transactionManager Bean
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JoyaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class))
                .withPropertyValues(
                        "joya.primary.use-spring=false",
                        "joya.primary.expose-transaction-manager=false",
                        "joya.primary.url=jdbc:h2:mem:" + db + "_off;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.primary.username=sa")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBeansOfType(PlatformTransactionManager.class).isEmpty());
                });
    }

    @Test
    void sidecarExposeTransactionManager_promotesPrimaryTmAndIsolatesNamedTransactional() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JoyaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class))
                .withUserConfiguration(MultiTxServiceConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:main_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:order_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].expose-transaction-manager=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    assertEquals(2, ctx.getBeansOfType(PlatformTransactionManager.class).size());
                    assertNotNull(ctx.getBean("transactionManager", PlatformTransactionManager.class));
                    assertNotNull(ctx.getBean("orderTransactionManager", PlatformTransactionManager.class));

                    JoyaClientRegistry registry = ctx.getBean(JoyaClientRegistry.class);
                    registry.primary().sql(
                            "CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT)").update();
                    registry.get("order").sql(
                            "CREATE TABLE t_order_record (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT)").update();

                    MultiTxService svc = ctx.getBean(MultiTxService.class);
                    // 无限定 @Transactional 不因多 TM 报错，精确落到主库并回滚
                    assertThrows(IllegalStateException.class, () -> svc.writePrimaryAndFail(10L));
                    assertEquals(0L, registry.primary().of(UserAccount.class).count());

                    // 命名 @Transactional("orderTransactionManager") 精确落到 order 库并回滚/提交
                    assertThrows(IllegalStateException.class, () -> svc.writeOrderAndFail(20L));
                    assertEquals(0L, registry.get("order").of(OrderRecord.class).count());

                    svc.writeOrderAndCommit(21L);
                    assertEquals(1L, registry.get("order").of(OrderRecord.class).count());
                });
    }

    @Test
    void selfManagedHikariDataSource_bindsMicrometerPoolMetricsAutomatically() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JoyaAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class,
                        JoyaActuatorAutoConfiguration.class))
                .withUserConfiguration(MetricsConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:hm_main_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "joya.datasources[0].name=metricsdb",
                        "joya.datasources[0].url=jdbc:h2:mem:hm_side_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    JoyaClient metricsClient = ctx.getBean("metricsdb", JoyaClient.class);
                    assertEquals(1, metricsClient.sql("SELECT 1").value(Integer.class).orElseThrow());

                    MeterRegistry meterRegistry = ctx.getBean(MeterRegistry.class);
                    assertNotNull(meterRegistry.find("hikaricp.connections")
                                    .tag("pool", "joya-metricsdb")
                                    .gauge(),
                            "自建 HikariDataSource 应自动向 MeterRegistry 暴露 pool=joya-metricsdb 指标");
                });
    }

    @Test
    void observationAutoConfiguration_recordsNormalStreamAndErrorLifecyclesWithoutLeak() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JoyaObservationAutoConfiguration.class,
                        JoyaAutoConfiguration.class))
                .withUserConfiguration(ObservationTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:obs_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "joya.observation.include-sql=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    JoyaClient joya = ctx.getBean(JoyaClient.class);
                    RecordingObservationHandler handler = ctx.getBean(RecordingObservationHandler.class);

                    joya.sql("CREATE TABLE obs_item (id INT PRIMARY KEY, name VARCHAR(32))").update();
                    joya.sql("INSERT INTO obs_item (id, name) VALUES (1, 'one'), (2, 'two')").update();
                    handler.started.clear();
                    handler.stopped.clear();
                    handler.errors.clear();

                    // 1. 普通查询：start 与 stop 同步成对完成，携带低/高基数标签
                    List<String> names = joya.sql("SELECT name FROM obs_item ORDER BY id").list(String.class);
                    assertEquals(List.of("one", "two"), names);
                    assertEquals(1, handler.started.size());
                    assertEquals(1, handler.stopped.size());
                    Observation.Context selectCtx = handler.stopped.get(0);
                    assertEquals("select", selectCtx.getLowCardinalityKeyValue("db.operation").getValue());
                    assertEquals("false", selectCtx.getLowCardinalityKeyValue("joya.streaming").getValue());
                    assertNotNull(selectCtx.getHighCardinalityKeyValue("db.statement"));

                    // 2. 流式查询：打开流后尚未 stop，关闭流时精确 stop
                    handler.started.clear();
                    handler.stopped.clear();
                    AtomicInteger consumed = new AtomicInteger();
                    try (Stream<String> stream = joya.sql("SELECT name FROM obs_item").stream(String.class)) {
                        assertEquals(1, handler.started.size());
                        assertEquals(0, handler.stopped.size(), "流未关闭前 Observation 应保持活跃");
                        stream.forEach(s -> consumed.incrementAndGet());
                    }
                    assertEquals(2, consumed.get());
                    assertEquals(1, handler.stopped.size(), "流关闭后 Observation 必须精确收口");
                    assertEquals("true", handler.stopped.get(0).getLowCardinalityKeyValue("joya.streaming").getValue());

                    // 3. 异常 SQL：记录 error 并正确 stop
                    handler.started.clear();
                    handler.stopped.clear();
                    handler.errors.clear();
                    assertThrows(RuntimeException.class, () -> joya.sql("SELECT * FROM no_such_table_obs").listMap());
                    assertEquals(1, handler.errors.size());
                    assertEquals(1, handler.stopped.size());
                });

        // joya.observation.enabled=false 退让验证
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JoyaObservationAutoConfiguration.class,
                        JoyaAutoConfiguration.class))
                .withUserConfiguration(ObservationTestConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:obs_off_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "joya.observation.enabled=false")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertFalse(ctx.containsBean("joyaObservationExecutionListener"));
                });
    }

    @Test
    void sidecarOnlyExposedTransactionManager_autoEnablesTransactionalProxyEvenWithoutPrimaryTm() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JoyaAutoConfiguration.class,
                        TransactionAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class))
                .withUserConfiguration(SidecarOnlyTxServiceConfig.class)
                .withPropertyValues(
                        "joya.primary.use-spring=false",
                        "joya.primary.expose-transaction-manager=false",
                        "joya.primary.url=jdbc:h2:mem:sp_main_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.primary.username=sa",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:sp_order_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].expose-transaction-manager=true")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    assertFalse(ctx.containsBean("transactionManager"));
                    assertNotNull(ctx.getBean("orderTransactionManager", PlatformTransactionManager.class));

                    JoyaClient orderClient = ctx.getBean("order", JoyaClient.class);
                    orderClient.sql("CREATE TABLE t_order_record (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT)")
                            .update();

                    SidecarOnlyTxService svc = ctx.getBean(SidecarOnlyTxService.class);
                    assertThrows(IllegalStateException.class, () -> svc.insertAndFail(99L));
                    assertEquals(0L, orderClient.of(OrderRecord.class).count(),
                            "无主 TM 仅旁路暴露 TM 时也必须自动激活 @EnableTransactionManagement 并回滚");
                });
    }

    @Test
    void earlyConnectedSelfManagedHikari_retainsPoolNameAndReceivesMicrometerMetrics() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JoyaAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class,
                        JoyaActuatorAutoConfiguration.class))
                .withUserConfiguration(MetricsConfig.class, EarlyConnectCustomizerConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:early_main_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "joya.datasources[0].name=earlydb",
                        "joya.datasources[0].url=jdbc:h2:mem:early_side_" + suffix + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> "startup failed: " + ctx.getStartupFailure());
                    MeterRegistry meterRegistry = ctx.getBean(MeterRegistry.class);
                    assertNotNull(meterRegistry.find("hikaricp.connections")
                                    .tag("pool", "joya-earlydb")
                                    .gauge(),
                            "即使在 SmartInitializingSingleton 之前提前建连，也应保留 joya-earlydb 池名并成功绑定指标");
                });
    }

    @Test
    void observationListener_evictsAbandonedStreamingContextViaWeakReference() throws Exception {
        RecordingObservationHandler handler = new RecordingObservationHandler();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        JoyaObservationExecutionListener listener = new JoyaObservationExecutionListener(registry, false);

        var stmt = com.sondertara.joya.core.kernel.statement.SqlStatement.of(
                "SELECT 1", List.of(), com.sondertara.joya.core.kernel.statement.StatementMetadata.empty());
        var leakedCtx = new com.sondertara.joya.core.kernel.observe.SqlExecutionContext(
                stmt, false, -1, "primary", null, true);
        listener.beforeExecute(leakedCtx);
        listener.afterExecute(leakedCtx, com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(-1L));
        assertEquals(1, listener.activeObservationCount());

        // 释放强引用并触发 GC，模拟调用方忘记关闭 Stream
        leakedCtx = null;
        for (int i = 0; i < 10 && listener.activeObservationCount() > 0; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(0, listener.activeObservationCount(),
                "遗弃未关闭的流上下文被 GC 回收后，活跃 Observation 条目应自动驱逐且不泄漏");
        assertEquals(1, handler.stopped.size(), "自动驱逐残留条目时应同步停止 Observation");
    }
}
