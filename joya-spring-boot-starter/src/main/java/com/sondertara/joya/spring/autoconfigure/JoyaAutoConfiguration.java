package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.spring.support.JoyaManagedTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.TransactionManager;

import javax.sql.DataSource;

/**
 * Joya Spring Boot 自动配置入口（旁路模式）：
 * <ul>
 *   <li>主库通过 {@code joya.primary} 配置（默认 {@code use-spring=true} 复用 Spring 原生 {@link DataSource}；
 *       若容器无任何 {@link DataSource} Bean 且未显式配置自管数据源，自动退让以兼容无库微服务）；</li>
 *   <li>扩展库通过 {@code joya.datasources} 数组配置并纳入 {@link JoyaClientRegistry}，不污染全局 DataSource Bean；</li>
 *   <li>装配细节分别委托 {@link JoyaClientAssembler} / {@link JoyaDataSourceFactory} /
 *       {@link JoyaTransactionManagerResolver} / {@link JoyaExtensionPointResolver}。</li>
 * </ul>
 */
@AutoConfiguration(
        afterName = {
                // 必须排在主 DataSource、Spring 事务管理器及 Flyway/Liquibase 迁移之后：
                // 1) 确保外部 DataSourceTransactionManager/JpaTransactionManager BeanDefinition 已注册；
                // 2) 确保数据库迁移在 Joya 客户端初始化与任意启动期查询之前完成建表。
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                "org.springframework.boot.autoconfigure.transaction.jta.JtaAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration"
        },
        beforeName = {
                // 排在 TransactionAutoConfiguration 之前，使自建主库自动暴露的 transactionManager 能够触发
                // Spring Boot 原生 @EnableTransactionManagement 与 TransactionTemplate 自动装配
                "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"
        }
)
@ConditionalOnClass({DataSource.class, JoyaClient.class})
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(JoyaAutoConfiguration.OnJoyaDataSourceAvailableCondition.class)
@EnableConfigurationProperties(JoyaProperties.class)
public class JoyaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JoyaAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public JoyaClientRegistry joyaClientRegistry(
            ObjectProvider<DataSource> springDataSourceProvider,
            JoyaProperties properties,
            Environment environment,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<JoyaClientCustomizer> customizersProvider) {
        return new JoyaClientAssembler(properties, environment, springDataSourceProvider, beanFactory)
                .assemble(customizersProvider.orderedStream().toList());
    }

    @Bean
    @Primary
    @Conditional(OnMissingPrimaryJoyaClientCondition.class)
    public JoyaClient joyaClient(JoyaClientRegistry registry) {
        return registry.primary();
    }

    /**
     * 自建主库（{@code joya.primary.use-spring=false} 且无外部 {@link TransactionManager} Bean）时，
     * 自动暴露主 {@code transactionManager}，使纯 Joya 服务开箱即用声明式 {@code @Transactional}。
     */
    @Bean(name = "transactionManager")
    @Primary
    @ConditionalOnMissingBean(TransactionManager.class)
    @Conditional(OnSelfManagedPrimaryTxManagerCondition.class)
    public JoyaManagedTransactionManager joyaPrimaryTransactionManager(JoyaClientRegistry registry) {
        return registry.primaryTransactionManager();
    }

    @Bean
    @ConditionalOnMissingBean(name = "joyaSqlLogListener")
    @Conditional(OnSqlLogOrSlowThresholdCondition.class)
    public SqlExecutionListener joyaSqlLogListener(JoyaProperties properties) {
        return JoyaSqlLogSupport.createListener(log, properties);
    }

    /**
     * 数据源可用性条件（运行于 {@link ConfigurationPhase#REGISTER_BEAN} 阶段，确保在 {@code DataSourceAutoConfiguration}
     * 注册完主库 {@link DataSource} BeanDefinition 后求值）：排除数据源自动配置的无库子服务在零配置下安静退让；
     * 一旦配置了自建主库、显式 {@code data-source-bean}、扩展库或错配了连接项，则正常进入装配以兑现校验提示。
     */
    static final class OnJoyaDataSourceAvailableCondition extends SpringBootCondition implements ConfigurationCondition {

        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Binder binder = Binder.get(context.getEnvironment());
            boolean useSpring = binder.bind("joya.primary.use-spring", Boolean.class).orElse(true);
            if (!useSpring) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaDataSource")
                        .found("self-managed primary").items("joya.primary.use-spring=false"));
            }
            String dsBean = binder.bind("joya.primary.data-source-bean", String.class).orElse("");
            if (!dsBean.isBlank()) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaDataSource")
                        .found("explicit data-source-bean").items(dsBean));
            }
            ConfigurableListableBeanFactory bf = context.getBeanFactory();
            if (bf != null && bf.getBeanNamesForType(DataSource.class, true, false).length > 0) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaDataSource")
                        .found("Spring DataSource bean").atAll());
            }
            if (hasExplicitDatasourceConfig(binder)) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaDataSource")
                        .found("explicit joya datasource properties").atAll());
            }
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaDataSource")
                    .didNotFind("any Spring DataSource bean or explicit Joya DataSource configuration").atAll());
        }

        private static boolean hasExplicitDatasourceConfig(Binder binder) {
            return binder.bind("joya.primary.url", String.class).isBound()
                    || binder.bind("joya.primary.jndi-name", String.class).isBound()
                    || binder.bind("joya.datasources[0].name", String.class).isBound()
                    || binder.bind("joya.datasources[0].url", String.class).isBound()
                    || binder.bind("joya.datasources[0].data-source-bean", String.class).isBound();
        }
    }

    /**
     * 主 {@link JoyaClient} 退让条件：仅当容器已存在名为 {@code "joyaClient"}、标记 {@code @Primary}、
     * 或未携带任何限定符的主 {@link JoyaClient} Bean 时才退让；用户仅声明带 {@code @Qualifier} 的副库客户端时，
     * 保持注册默认 {@code @Primary} 主库客户端，杜绝未限定注入点静默落到副库。
     */
    static final class OnMissingPrimaryJoyaClientCondition extends SpringBootCondition implements ConfigurationCondition {

        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConfigurableListableBeanFactory bf = context.getBeanFactory();
            if (bf == null) {
                return ConditionOutcome.match();
            }
            if (bf.containsBean("joyaClient")) {
                return ConditionOutcome.noMatch(ConditionMessage.forCondition("PrimaryJoyaClient")
                        .found("bean named 'joyaClient'").items("joyaClient"));
            }
            for (String beanName : bf.getBeanNamesForType(JoyaClient.class, true, false)) {
                if (bf.containsBeanDefinition(beanName)) {
                    BeanDefinition bd = bf.getMergedBeanDefinition(beanName);
                    if (bd.isPrimary()) {
                        return ConditionOutcome.noMatch(ConditionMessage.forCondition("PrimaryJoyaClient")
                                .found("@Primary JoyaClient bean").items(beanName));
                    }
                }
                JoyaExtensionPointResolver.QualifierMetadata qualifiers =
                        JoyaExtensionPointResolver.extractQualifiers(bf, beanName);
                if (!qualifiers.hasAnyQualifier()) {
                    return ConditionOutcome.noMatch(ConditionMessage.forCondition("PrimaryJoyaClient")
                            .found("unqualified JoyaClient bean").items(beanName));
                }
            }
            return ConditionOutcome.match(ConditionMessage.forCondition("PrimaryJoyaClient")
                    .didNotFind("primary or unqualified JoyaClient bean").atAll());
        }
    }

    static final class OnSqlLogOrSlowThresholdCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Binder binder = Binder.get(context.getEnvironment());
            boolean sqlLog = binder.bind("joya.sql-log", Boolean.class).orElse(false);
            if (sqlLog) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaSqlLog")
                        .found("property").items("joya.sql-log=true"));
            }
            long slowThresholdMs = binder.bind("joya.slow-sql-threshold-ms", Long.class).orElse(0L);
            if (slowThresholdMs > 0L) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaSqlLog")
                        .found("property").items("joya.slow-sql-threshold-ms=" + slowThresholdMs));
            }
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSqlLog")
                    .didNotFind("enabled joya.sql-log or positive joya.slow-sql-threshold-ms").atAll());
        }
    }

    static final class OnSelfManagedPrimaryTxManagerCondition extends SpringBootCondition
            implements ConfigurationCondition {

        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Binder binder = Binder.get(context.getEnvironment());
            boolean useSpring = binder.bind("joya.primary.use-spring", Boolean.class).orElse(true);
            if (useSpring) {
                return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSelfManagedPrimaryTxManager")
                        .because("joya.primary.use-spring is true"));
            }
            String dsBean = binder.bind("joya.primary.data-source-bean", String.class).orElse("");
            if (!dsBean.isBlank()) {
                return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSelfManagedPrimaryTxManager")
                        .because("joya.primary.data-source-bean is configured"));
            }
            Boolean expose = binder.bind("joya.primary.expose-transaction-manager", Boolean.class).orElse(null);
            if (Boolean.FALSE.equals(expose)) {
                return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSelfManagedPrimaryTxManager")
                        .because("joya.primary.expose-transaction-manager is false"));
            }
            return ConditionOutcome.match(ConditionMessage.forCondition("JoyaSelfManagedPrimaryTxManager")
                    .found("self-managed primary DataSource without expose-transaction-manager=false").atAll());
        }
    }
}
