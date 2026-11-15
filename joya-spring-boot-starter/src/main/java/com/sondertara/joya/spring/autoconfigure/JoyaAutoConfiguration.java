package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Joya Spring Boot 自动配置入口（旁路模式）：
 * <ul>
 *   <li>主库通过 {@code joya.primary} 配置（默认 {@code use-spring=true} 复用 Spring 原生 {@link DataSource}）；</li>
 *   <li>扩展库通过 {@code joya.datasources} 数组配置并纳入 {@link JoyaClientRegistry}，不污染全局 DataSource Bean；</li>
 *   <li>装配细节分别委托 {@link JoyaClientAssembler} / {@link JoyaDataSourceFactory} /
 *       {@link JoyaTransactionManagerResolver} / {@link JoyaExtensionPointResolver}。</li>
 * </ul>
 */
@AutoConfiguration(afterName = {
        // 必须排在主 DataSource 与事务管理器装配之后，否则解析时事务管理器 Bean 定义尚未注册
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@ConditionalOnClass({DataSource.class, JoyaClient.class})
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JoyaProperties.class)
public class JoyaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JoyaAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public JoyaClientRegistry joyaClientRegistry(
            ObjectProvider<DataSource> springDataSourceProvider,
            ObjectProvider<PlatformTransactionManager> txManagerProvider,
            JoyaProperties properties,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<JoyaClientCustomizer> customizersProvider) {
        return new JoyaClientAssembler(properties, springDataSourceProvider, txManagerProvider, beanFactory)
                .assemble(customizersProvider.orderedStream().toList());
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public JoyaClient joyaClient(JoyaClientRegistry registry) {
        return registry.primary();
    }

    @Bean
    @ConditionalOnMissingBean(name = "joyaSqlLogListener")
    @Conditional(OnSqlLogOrSlowThresholdCondition.class)
    public SqlExecutionListener joyaSqlLogListener(JoyaProperties properties) {
        return JoyaSqlLogSupport.createListener(log, properties);
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
}
