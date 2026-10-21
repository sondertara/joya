package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.spring.support.SpringConnectionProvider;
import com.sondertara.joya.spring.support.SpringTxEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.MethodMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joya Spring Boot 自动配置（旁路模式）：
 * <ul>
 *   <li>主库通过 {@code joya.primary} 配置（默认 {@code use-spring=true} 复用 Spring 原生 {@link DataSource}，也可设为 {@code false} 自建主库）；</li>
 *   <li>扩展库通过 {@code joya.datasources} 数组配置并纳入 {@link JoyaClientRegistry}，不污染全局 {@link DataSource} Bean；</li>
 *   <li>主库与所有扩展库均作为同构的 {@link JoyaDataSourceProperties}，走统一的客户端构建流程装入 {@link JoyaClientRegistry}。</li>
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

        List<JoyaClientCustomizer> customizers = customizersProvider.orderedStream().toList();
        Map<String, JoyaClient> clients = new LinkedHashMap<>();
        List<AutoCloseable> managedResources = new ArrayList<>();

        // 1. 构建主库客户端（joya.primary）
        PrimaryDataSourceProperties primaryProps = properties.getPrimary();
        String primaryName = (primaryProps.getName() == null || primaryProps.getName().isBlank())
                ? JoyaClientRegistry.PRIMARY
                : primaryProps.getName().trim();

        DataSource primaryDataSource;
        PlatformTransactionManager primaryTxManager;
        if (primaryProps.isUseSpring()) {
            if (hasCustomConnectionConfig(primaryProps)) {
                log.warn("[Joya] 'joya.primary.use-spring' is true: custom connection properties under 'joya.primary' "
                        + "(url/username/password/driver-class-name) will be ignored and Spring's native DataSource will be used instead. "
                        + "Set 'joya.primary.use-spring=false' if you want to use the custom primary datasource.");
            }
            primaryDataSource = springDataSourceProvider.getIfAvailable();
            if (primaryDataSource == null) {
                throw new IllegalStateException(
                        "joya.primary.use-spring is true, but no Spring DataSource bean was found. "
                                + "Configure spring.datasource.* or set joya.primary.use-spring=false with joya.primary.url.");
            }
            primaryTxManager = resolvePrimaryTransactionManager(primaryDataSource, txManagerProvider);
        } else {
            primaryDataSource = primaryProps.initializeDataSourceBuilder().build();
            if (primaryDataSource instanceof AutoCloseable closeable) {
                managedResources.add(closeable);
            }
            primaryTxManager = new DataSourceTransactionManager(primaryDataSource);
        }

        clients.put(primaryName, buildClient(
                primaryName,
                primaryProps,
                primaryDataSource,
                primaryTxManager,
                properties,
                beanFactory,
                customizers
        ));

        // 2. 用完全相同的构建流程创建扩展数据源列表（joya.datasources）
        for (JoyaDataSourceProperties dsProps : properties.getDatasources()) {
            String name = dsProps.getName() == null ? "" : dsProps.getName().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Each item in joya.datasources must specify a non-empty 'name'.");
            }
            if (clients.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate Joya datasource name '" + name + "'.");
            }
            DataSource extraDataSource = dsProps.initializeDataSourceBuilder().build();
            if (extraDataSource instanceof AutoCloseable closeable) {
                managedResources.add(closeable);
            }
            PlatformTransactionManager extraTxManager = new DataSourceTransactionManager(extraDataSource);
            clients.put(name, buildClient(
                    name,
                    dsProps,
                    extraDataSource,
                    extraTxManager,
                    properties,
                    beanFactory,
                    customizers
            ));
        }

        return new JoyaClientRegistry(primaryName, clients, managedResources);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public JoyaClient joyaClient(JoyaClientRegistry registry) {
        return registry.primary();
    }

    @Bean
    @ConditionalOnProperty(prefix = "joya", name = "sql-log", havingValue = "true")
    public SqlInterceptor joyaSqlLogInterceptor() {
        return (context, chain) -> {
            log.info("[Joya SQL] {} | args={}", context.statement().sql(), context.statement().args());
            return chain.proceed(context);
        };
    }

    private static JoyaClient buildClient(
            String clientName,
            JoyaDataSourceProperties dsProps,
            DataSource dataSource,
            PlatformTransactionManager txManager,
            JoyaProperties rootProperties,
            ConfigurableListableBeanFactory beanFactory,
            List<JoyaClientCustomizer> customizers) {

        JoyaSettings settings = rootProperties.toSettings(dsProps);
        DialectStrategy dialectStrategy = rootProperties.toDialectStrategy(dsProps, dataSource);
        SpringConnectionProvider connectionProvider = new SpringConnectionProvider(dataSource, settings.queryTimeoutSeconds());
        SpringTxEngine txEngine = new SpringTxEngine(dataSource, txManager);
        List<SqlInterceptor> interceptors = resolveInterceptorsForClient(beanFactory, clientName);

        JoyaClient.Builder builder = JoyaClient.builder(dataSource)
                .settings(settings)
                .dialectStrategy(dialectStrategy)
                .connectionProvider(connectionProvider)
                .txEngine(txEngine)
                .interceptors(interceptors);

        for (JoyaClientCustomizer customizer : customizers) {
            customizer.customize(clientName, dataSource, builder);
        }
        return builder.build();
    }

    private static boolean hasCustomConnectionConfig(PrimaryDataSourceProperties props) {
        return isNotBlank(props.getUrl())
                || isNotBlank(props.getUsername())
                || isNotBlank(props.getPassword())
                || isNotBlank(props.getDriverClassName())
                || isNotBlank(props.getJndiName());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static PlatformTransactionManager resolvePrimaryTransactionManager(
            DataSource primaryDataSource,
            ObjectProvider<PlatformTransactionManager> txManagerProvider) {
        PlatformTransactionManager ifUnique = txManagerProvider.getIfUnique();
        if (ifUnique != null) {
            return ifUnique;
        }
        for (PlatformTransactionManager tm : txManagerProvider) {
            if (tm instanceof ResourceTransactionManager rtm && rtm.getResourceFactory() == primaryDataSource) {
                return tm;
            }
        }
        return new DataSourceTransactionManager(primaryDataSource);
    }

    private static List<SqlInterceptor> resolveInterceptorsForClient(
            ConfigurableListableBeanFactory beanFactory,
            String clientName) {
        String[] beanNames = beanFactory.getBeanNamesForType(SqlInterceptor.class, false, false);
        if (beanNames.length == 0) {
            return List.of();
        }
        List<SqlInterceptor> matched = new ArrayList<>();
        for (String beanName : beanNames) {
            Set<String> qualifiers = extractQualifierValues(beanFactory, beanName);
            if (qualifiers.isEmpty() || qualifiers.contains(clientName)) {
                matched.add(beanFactory.getBean(beanName, SqlInterceptor.class));
            }
        }
        return matched;
    }

    private static Set<String> extractQualifierValues(ConfigurableListableBeanFactory beanFactory, String beanName) {
        Set<String> values = new LinkedHashSet<>();
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return values;
        }
        BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);
        if (bd instanceof AbstractBeanDefinition abd) {
            for (AutowireCandidateQualifier q : abd.getQualifiers()) {
                Object val = q.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
                if (val instanceof String s && !s.isBlank()) {
                    values.add(s);
                }
            }
        }
        if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
            MethodMetadata factoryMethod = annotatedBd.getFactoryMethodMetadata();
            if (factoryMethod != null) {
                collectQualifierFromAnnotations(factoryMethod.getAnnotations(), values);
            }
            collectQualifierFromAnnotations(annotatedBd.getMetadata().getAnnotations(), values);
        }
        return values;
    }

    private static void collectQualifierFromAnnotations(MergedAnnotations annotations, Set<String> values) {
        for (MergedAnnotation<Annotation> ann : annotations) {
            if (Qualifier.class.equals(ann.getType())) {
                String val = ann.getString("value");
                if (!val.isBlank()) {
                    values.add(val);
                }
            }
        }
    }
}
