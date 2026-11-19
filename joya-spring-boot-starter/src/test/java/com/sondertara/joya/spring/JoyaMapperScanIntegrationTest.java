package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.spring.annotation.JoyaMapperScan;
import com.sondertara.joya.spring.autoconfigure.JoyaAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaNamedClientsAutoConfiguration;
import com.sondertara.joya.spring.test.mixed.BaseGenericMapper;
import com.sondertara.joya.spring.test.mixed.QualifiedOrderMapper;
import com.sondertara.joya.spring.test.order.OrderRecord;
import com.sondertara.joya.spring.test.order.OrderRecordMapper;
import com.sondertara.joya.spring.test.user.UserAccount;
import com.sondertara.joya.spring.test.user.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JoyaMapperScan} 与主库 {@code @Qualifier} 对称性集成测试。
 */
class JoyaMapperScanIntegrationTest {

    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(false)
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JoyaAutoConfiguration.class,
                        JoyaNamedClientsAutoConfiguration.class
                ));
    }

    private static UserAccount user(Long id, String name, int age) {
        UserAccount u = new UserAccount();
        u.setId(id);
        u.setUserName(name);
        u.setAge(age);
        return u;
    }

    private static OrderRecord order(Long id, String orderNo, int amount) {
        OrderRecord o = new OrderRecord();
        o.setId(id);
        o.setOrderNo(orderNo);
        o.setAmount(amount);
        return o;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    static class SingleDsMapperScanConfig {

        @Bean
        public TransactionalUserService transactionalUserService(UserAccountMapper userMapper) {
            return new TransactionalUserService(userMapper);
        }
    }

    static class TransactionalUserService {
        private final UserAccountMapper userMapper;

        TransactionalUserService(UserAccountMapper userMapper) {
            this.userMapper = userMapper;
        }

        @Transactional
        public void createUser(Long id, String name, boolean fail) {
            userMapper.insert(user(id, name, 26));
            if (fail) {
                throw new IllegalStateException("simulated rollback");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    @JoyaMapperScan(basePackageClasses = OrderRecordMapper.class, client = "order")
    static class RepeatableMultiDsConfig {

        @Autowired
        JoyaClient primaryByDefault;

        @Autowired
        @Qualifier("mainUser")
        JoyaClient primaryByCustomQualifier;

        @Autowired
        @Qualifier("order")
        JoyaClient orderClient;

        @Autowired
        UserAccountMapper userMapper;

        @Autowired
        OrderRecordMapper orderMapper;
    }

    @Configuration(proxyBeanMethods = false)
    @JoyaMapperScan(basePackageClasses = QualifiedOrderMapper.class)
    static class InterfaceQualifierAndGenericSkipConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    @Import(ChildCustomMapperBeanConfig.class)
    static class RootScanWithChildCustomBeanConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class ChildCustomMapperBeanConfig {

        static final UserAccountMapper CUSTOM_INSTANCE = (UserAccountMapper) Proxy.newProxyInstance(
                UserAccountMapper.class.getClassLoader(),
                new Class<?>[]{UserAccountMapper.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "CustomUserAccountMapper";
                    }
                    throw new UnsupportedOperationException();
                });

        /**
         * 方法名故意采用与扫描默认 Bean 名完全相同的 {@code userAccountMapper}，
         * 验证在根配置开启 {@code @JoyaMapperScan}、子配置声明同名 {@code @Bean} 时不会发生覆盖冲突。
         */
        @Bean
        public UserAccountMapper userAccountMapper() {
            return CUSTOM_INSTANCE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class, client = "primary")
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class, client = "order")
    static class SameMapperTwoDatasourcesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    static class StandaloneManualJoyaClientConfig {
        @Bean
        public JoyaClient manualJoyaClient() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:manual_standalone_" + System.nanoTime() + ";MODE=MYSQL;DB_CLOSE_DELAY=-1");
            return JoyaClient.create(ds);
        }
    }

    @Test
    void singleDatasourceScan_injectsMapper_participatesInSpringTx_andQualifiesPrimaryClient() {
        String dbName = "mapper_single_" + System.nanoTime();
        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource"
                )
                .withUserConfiguration(SingleDsMapperScanConfig.class)
                .run(ctx -> {
                    JoyaClient unqualified = ctx.getBean(JoyaClient.class);
                    JoyaClient qualifiedPrimary = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                            ctx.getBeanFactory(), JoyaClient.class, "primary");
                    assertSame(unqualified, qualifiedPrimary, "单数据源默认主库也应支持 @Qualifier(\"primary\") 对称注入");

                    unqualified.script("CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT);");

                    UserAccountMapper mapper = ctx.getBean(UserAccountMapper.class);
                    TransactionalUserService txService = ctx.getBean(TransactionalUserService.class);

                    assertThrows(IllegalStateException.class, () -> txService.createUser(1L, "RolledBack", true));
                    assertEquals(0L, mapper.count(), "@Autowired Mapper 在 @Transactional 内抛异常必须随事务回滚");

                    txService.createUser(2L, "Committed", false);
                    assertEquals("Committed", mapper.findByUserName("Committed").orElseThrow().getUserName());
                });
    }

    @Test
    void repeatableMultiDatasourceScan_routesMappersByPackage_andSupportsCustomPrimaryQualifier() {
        String userDb = "mapper_user_" + System.nanoTime();
        String orderDb = "mapper_order_" + System.nanoTime();
        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + userDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.primary.name=mainUser",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:" + orderDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource"
                )
                .withUserConfiguration(RepeatableMultiDsConfig.class)
                .run(ctx -> {
                    RepeatableMultiDsConfig cfg = ctx.getBean(RepeatableMultiDsConfig.class);
                    assertSame(cfg.primaryByDefault, cfg.primaryByCustomQualifier);
                    assertNotSame(cfg.primaryByDefault, cfg.orderClient);

                    cfg.primaryByDefault.script(
                            "CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT);");
                    cfg.orderClient.script(
                            "CREATE TABLE t_order_record (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT);");

                    assertEquals(1, cfg.userMapper.insert(user(10L, "Alice", 20)));
                    assertEquals(1, cfg.orderMapper.insert(order(100L, "ORD-100", 500)));

                    assertEquals("Alice", cfg.userMapper.findByUserName("Alice").orElseThrow().getUserName());
                    assertEquals(500, cfg.orderMapper.findByOrderNo("ORD-100").orElseThrow().getAmount());
                    assertSame(cfg.primaryByDefault, cfg.userMapper.db());
                    assertSame(cfg.orderClient, cfg.orderMapper.db());
                });
    }

    @Test
    void interfaceLevelQualifier_overridesPackageDefault_andSkipsUnparameterizedGenericBaseInterface() {
        String userDb = "mapper_mixed_user_" + System.nanoTime();
        String orderDb = "mapper_mixed_order_" + System.nanoTime();
        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + userDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:" + orderDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource"
                )
                .withUserConfiguration(InterfaceQualifierAndGenericSkipConfig.class)
                .run(ctx -> {
                    // BaseGenericMapper 只有 1 个具体子接口 Bean，其自身绝不应被单独注册
                    String[] baseBeans = ctx.getBeanNamesForType(BaseGenericMapper.class);
                    assertEquals(1, baseBeans.length);

                    JoyaClient orderClient = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                            ctx.getBeanFactory(), JoyaClient.class, "order");
                    orderClient.script(
                            "CREATE TABLE t_order_record (id BIGINT PRIMARY KEY, order_no VARCHAR(64), amount INT);");

                    QualifiedOrderMapper mapper = ctx.getBean(QualifiedOrderMapper.class);
                    assertSame(orderClient, mapper.db(), "接口上的 @Qualifier(\"order\") 必须优先于 @JoyaMapperScan 默认主库");
                    mapper.insert(order(1L, "ORD-Q1", 99));
                    assertEquals(99, mapper.findByOrderNo("ORD-Q1").orElseThrow().getAmount());
                });
    }

    @Test
    void userDefinedMapperBeanInChildConfigWithSameName_takesPrecedenceWithoutBeanOverrideConflict() {
        String dbName = "mapper_custom_backoff_" + System.nanoTime();
        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource"
                )
                .withUserConfiguration(RootScanWithChildCustomBeanConfig.class)
                .run(ctx -> {
                    UserAccountMapper mapper = ctx.getBean(UserAccountMapper.class);
                    assertSame(ChildCustomMapperBeanConfig.CUSTOM_INSTANCE, mapper,
                            "子配置类声明同名 @Bean userAccountMapper() 时，@JoyaMapperScan 必须退让且不抛覆盖异常");
                });
    }

    @Test
    void factoryBeanMetadata_predictsMapperTypeWithoutPrematureRegistryOrDataSourceInitialization() {
        String dbName = "mapper_lazy_type_" + System.nanoTime();
        AtomicReference<String[]> earlyNames = new AtomicReference<>();
        AtomicBoolean registryAlreadyCreated = new AtomicBoolean(true);

        BeanFactoryPostProcessor earlyTypeInspector = beanFactory -> {
            earlyNames.set(beanFactory.getBeanNamesForType(UserAccountMapper.class, true, false));
            registryAlreadyCreated.set(beanFactory.containsSingleton("joyaClientRegistry"));
        };

        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource"
                )
                .withBean(BeanFactoryPostProcessor.class, () -> earlyTypeInspector)
                .withUserConfiguration(SingleDsMapperScanConfig.class)
                .run(ctx -> {
                    assertNotNull(earlyNames.get());
                    assertTrue(Arrays.asList(earlyNames.get()).contains("userAccountMapper"),
                            "BeanFactoryPostProcessor 阶段必须无需实例化即可识别 UserAccountMapper 类型");
                    assertFalse(registryAlreadyCreated.get(),
                            "类型匹配阶段绝不能提前实例化 joyaClientRegistry 或底层连接池");
                });
    }

    @Test
    void sameMapperInterfaceScannedForDefaultPrimaryAndSecondary_deduplicatesPrimaryAndSupportsPrimaryPlusQualifier() {
        String primaryDb = "mapper_shard_p_" + System.nanoTime();
        String orderDb = "mapper_shard_o_" + System.nanoTime();
        baseRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + primaryDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:" + orderDb + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource"
                )
                .withUserConfiguration(SameMapperTwoDatasourcesConfig.class)
                .run(ctx -> {
                    assertEquals(2, ctx.getBeanNamesForType(UserAccountMapper.class).length,
                            "client=\"\" 与 client=\"primary\" 应识别为同一主库并幂等去重");
                    UserAccountMapper unqualifiedPrimaryMapper = ctx.getBean(UserAccountMapper.class);
                    UserAccountMapper primaryMapper = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                            ctx.getBeanFactory(), UserAccountMapper.class, "primary");
                    UserAccountMapper orderMapper = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                            ctx.getBeanFactory(), UserAccountMapper.class, "order");
                    assertSame(unqualifiedPrimaryMapper, primaryMapper,
                            "同接口多库注册时，主库 Mapper 应兼具 @Primary 与 @Qualifier(\"primary\")");
                    assertNotSame(primaryMapper, orderMapper);

                    primaryMapper.db().script(
                            "CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT);");
                    orderMapper.db().script(
                            "CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT);");

                    primaryMapper.insert(user(1L, "FromPrimary", 18));
                    orderMapper.insert(user(1L, "FromOrder", 28));

                    assertEquals("FromPrimary", primaryMapper.findById(1L).orElseThrow().getUserName());
                    assertEquals("FromOrder", orderMapper.findById(1L).orElseThrow().getUserName());
                });
    }

    @Test
    void standaloneManualJoyaClientWithoutAutoConfigOrRegistry_supportsMapperScanAndPrimaryQualifier() {
        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(false)
                .withUserConfiguration(StandaloneManualJoyaClientConfig.class)
                .run(ctx -> {
                    UserAccountMapper unqualified = ctx.getBean(UserAccountMapper.class);
                    UserAccountMapper qualified = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                            ctx.getBeanFactory(), UserAccountMapper.class, "primary");
                    assertSame(unqualified, qualified);
                    unqualified.db().script(
                            "CREATE TABLE t_user_account (id BIGINT PRIMARY KEY, user_name VARCHAR(64), age INT);");
                    unqualified.insert(user(7L, "Standalone", 30));
                    assertEquals("Standalone", unqualified.findById(7L).orElseThrow().getUserName());
                });
    }
}
