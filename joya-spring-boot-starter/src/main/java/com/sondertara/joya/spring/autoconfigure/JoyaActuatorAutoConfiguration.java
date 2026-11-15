package com.sondertara.joya.spring.autoconfigure;

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

import javax.sql.DataSource;
import java.util.Map;

/**
 * Spring Boot Actuator 可选健康检查自动配置：
 * 为 Joya 自建托管（旁路或 {@code use-spring=false}）的 {@link DataSource} 注册聚合 {@link HealthContributor}，
 * 复用的 Spring 容器数据源则由 Spring Boot 自带 {@code db} 健康检查负责，避免重复探活。
 */
@AutoConfiguration(after = JoyaAutoConfiguration.class)
@ConditionalOnClass({
        HealthContributor.class,
        DataSourceHealthIndicator.class,
        ConditionalOnEnabledHealthIndicator.class,
        JoyaClientRegistry.class
})
@ConditionalOnBean(JoyaClientRegistry.class)
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnEnabledHealthIndicator("joya")
public class JoyaActuatorAutoConfiguration {

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
