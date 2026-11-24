package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.spring.support.JoyaObservationExecutionListener;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer Observation 自动配置：当 classpath 存在 {@link ObservationRegistry} 且容器中存在相应 Bean 时，
 * 自动注册全局 {@link JoyaObservationExecutionListener}，使所有 Joya SQL 执行自动接入 Metrics 与分布式追踪（Tracing）。
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"
        },
        before = JoyaAutoConfiguration.class
)
@ConditionalOnClass({ObservationRegistry.class, Observation.class, JoyaClient.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "joya.observation", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JoyaProperties.class)
public class JoyaObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "joyaObservationExecutionListener")
    public SqlExecutionListener joyaObservationExecutionListener(
            ObservationRegistry observationRegistry,
            JoyaProperties properties) {
        return new JoyaObservationExecutionListener(
                observationRegistry,
                properties.getObservation().isIncludeSql());
    }
}
