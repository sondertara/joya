package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
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
    @ConditionalOnProperty(prefix = "joya", name = "sql-log", havingValue = "true")
    public SqlExecutionListener joyaSqlLogListener(JoyaProperties properties) {
        return new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext context) {
                if (context.batch()) {
                    log.info("[Joya SQL] {} | batch rows={}", context.statement().sql(),
                            context.attributes().get(SqlExecutionContext.ATTR_BATCH_ROWS));
                } else if (properties.isSqlLogPrintArgs()) {
                    log.info("[Joya SQL] {} | args={}", context.statement().sql(), context.statement().args());
                } else {
                    log.info("[Joya SQL] {} | paramCount={}", context.statement().sql(),
                            context.statement().args().size());
                }
            }
        };
    }
}
