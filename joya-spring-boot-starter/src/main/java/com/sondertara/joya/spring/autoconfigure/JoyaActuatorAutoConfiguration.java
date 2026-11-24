package com.sondertara.joya.spring.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Spring Boot Actuator &amp; Micrometer 可选自动配置：
 * <ul>
 *   <li>为 Joya 自建托管（旁路或 {@code use-spring=false}）的 {@link DataSource} 注册聚合 {@link HealthContributor}，
 *       复用的 Spring 容器数据源则由 Spring Boot 自带 {@code db} 健康检查负责，避免重复探活；</li>
 *   <li>当容器中存在 {@link MeterRegistry} 时，自动将 {@link MicrometerMetricsTrackerFactory} 绑定到 Joya 自建的
 *       {@link HikariDataSource} 实例，使其连接池指标（{@code hikaricp.*}）纳入监控体系。</li>
 * </ul>
 */
@AutoConfiguration(
        after = JoyaAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        }
)
@ConditionalOnClass(JoyaClientRegistry.class)
@ConditionalOnBean(JoyaClientRegistry.class)
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoyaActuatorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JoyaActuatorAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({
            HealthContributor.class,
            DataSourceHealthIndicator.class,
            ConditionalOnEnabledHealthIndicator.class
    })
    @ConditionalOnEnabledHealthIndicator("joya")
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "joyaHealthContributor")
        public HealthContributor joyaHealthContributor(JoyaClientRegistry registry) {
            Map<String, DataSource> sidecars = registry.sidecarDataSources();
            if (sidecars.isEmpty()) {
                return (HealthIndicator) () -> Health.up()
                        .withDetail("clients", registry.names())
                        .withDetail("sidecarDataSources", 0)
                        .build();
            }
            return CompositeHealthContributor.fromMap(sidecars, DataSourceHealthIndicator::new);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({MeterRegistry.class, HikariDataSource.class, MicrometerMetricsTrackerFactory.class})
    @ConditionalOnBean(MeterRegistry.class)
    static class HikariMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "joyaHikariMetricsBinder")
        public SmartInitializingSingleton joyaHikariMetricsBinder(JoyaClientRegistry registry,
                                                                  MeterRegistry meterRegistry) {
            return () -> bindHikariMetrics(registry, meterRegistry);
        }

        private static void bindHikariMetrics(JoyaClientRegistry registry, MeterRegistry meterRegistry) {
            Map<String, DataSource> sidecars = registry.sidecarDataSources();
            if (sidecars.isEmpty()) {
                return;
            }
            MicrometerMetricsTrackerFactory factory = new MicrometerMetricsTrackerFactory(meterRegistry);
            for (Map.Entry<String, DataSource> entry : sidecars.entrySet()) {
                DataSource ds = entry.getValue();
                if (!(ds instanceof HikariDataSource hikari)) {
                    continue;
                }
                if (hikari.getMetricsTrackerFactory() != null || hikari.getMetricRegistry() != null) {
                    continue;
                }
                if (!hikari.isRunning()) {
                    String currentPoolName = hikari.getPoolName();
                    if (currentPoolName == null || currentPoolName.isBlank()
                            || currentPoolName.startsWith("HikariPool-")) {
                        hikari.setPoolName("joya-" + entry.getKey());
                    }
                }
                try {
                    hikari.setMetricsTrackerFactory(factory);
                } catch (IllegalStateException ex) {
                    log.debug("Unable to bind Micrometer metrics onto already-sealed HikariDataSource '{}': {}",
                            entry.getKey(), ex.getMessage());
                }
            }
        }
    }
}
