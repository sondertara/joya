package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.spring.support.SpringConnectionProvider;
import com.sondertara.joya.spring.support.SpringTxEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
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
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.InfrastructureProxy;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.MethodMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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

        try {
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
                primaryDataSource = resolvePrimaryDataSource(primaryName, springDataSourceProvider, beanFactory);
                primaryTxManager = resolvePrimaryTransactionManager(primaryDataSource, txManagerProvider);
            } else {
                validateSelfManagedDataSource("joya.primary", primaryProps);
                primaryDataSource = buildSelfManagedDataSource("joya.primary", primaryProps);
                if (primaryDataSource instanceof AutoCloseable closeable) {
                    managedResources.add(closeable);
                }
                primaryTxManager = null;
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
            List<JoyaDataSourceProperties> extraDataSources = properties.getDatasources();
            for (int index = 0; index < extraDataSources.size(); index++) {
                JoyaDataSourceProperties dsProps = extraDataSources.get(index);
                String name = dsProps.getName() == null ? "" : dsProps.getName().trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Each item in joya.datasources must specify a non-empty 'name'.");
                }
                if (clients.containsKey(name)) {
                    throw new IllegalArgumentException("Duplicate Joya datasource name '" + name + "'.");
                }
                validateSelfManagedDataSource("joya.datasources[" + index + "]", dsProps);
                DataSource extraDataSource = buildSelfManagedDataSource("joya.datasources[" + index + "]", dsProps);
                if (extraDataSource instanceof AutoCloseable closeable) {
                    managedResources.add(closeable);
                }
                clients.put(name, buildClient(
                        name,
                        dsProps,
                        extraDataSource,
                        null,
                        properties,
                        beanFactory,
                        customizers
                ));
            }

            return new JoyaClientRegistry(primaryName, clients, managedResources);
        } catch (RuntimeException | Error failure) {
            closeManagedResourcesAfterFailure(managedResources, failure);
            throw failure;
        }
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public JoyaClient joyaClient(JoyaClientRegistry registry) {
        return registry.primary();
    }

    @Bean
    @ConditionalOnProperty(prefix = "joya", name = "sql-log", havingValue = "true")
    public SqlInterceptor joyaSqlLogInterceptor(JoyaProperties properties) {
        return (context, chain) -> {
            if (context.isBatch()) {
                log.info("[Joya SQL] {} | batch rows={}", context.statement().sql(),
                        context.attr(RewriteContext.ATTR_BATCH_ROWS));
            } else if (properties.isSqlLogPrintArgs()) {
                log.info("[Joya SQL] {} | args={}", context.statement().sql(), context.statement().args());
            } else {
                log.info("[Joya SQL] {} | paramCount={}", context.statement().sql(), context.statement().args().size());
            }
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
        if ((dsProps != null && dsProps.getDialect() != null && Boolean.TRUE.equals(dsProps.getDynamicDialect()))
                || (rootProperties.getDialect() != null && rootProperties.isDynamicDialect())) {
            log.warn("[Joya] Both 'dialect' and 'dynamic-dialect=true' are configured on datasource '{}'; "
                    + "'dynamic-dialect' takes precedence and 'dialect' will be ignored.", clientName);
        }
        DialectStrategy dialectStrategy = rootProperties.toDialectStrategy(dsProps, dataSource);
        // 扩展点按类型 + @Qualifier(clientName) 路由：无限定符的 Bean 对所有客户端生效，带限定符的只对同名客户端生效
        JoyaClient.Builder builder = JoyaClient.builder(dataSource)
                .settings(settings)
                .dialectStrategy(dialectStrategy)
                .interceptors(resolveForClient(beanFactory, SqlInterceptor.class, clientName))
                .rewriters(resolveForClient(beanFactory, SqlRewriter.class, clientName))
                .executionListeners(resolveForClient(beanFactory, SqlExecutionListener.class, clientName))
                .entityListeners(resolveForClient(beanFactory, EntityListener.class, clientName));
        RowMapperFactory rowMapperFactory = resolveSingleForClient(beanFactory, RowMapperFactory.class, clientName);
        if (rowMapperFactory != null) {
            builder.rowMapperFactory(rowMapperFactory);
        }
        SqlExceptionTranslator exceptionTranslator =
                resolveSingleForClient(beanFactory, SqlExceptionTranslator.class, clientName);
        if (exceptionTranslator != null) {
            builder.exceptionTranslator(exceptionTranslator);
        }

        if (txManager != null) {
            SpringConnectionProvider connectionProvider = new SpringConnectionProvider(dataSource, settings.executionOptions().queryTimeoutSeconds());
            SpringTxEngine txEngine = new SpringTxEngine(dataSource, txManager);
            builder.connectionProvider(connectionProvider).txEngine(txEngine);
        }

        for (JoyaClientCustomizer customizer : customizers) {
            customizer.customize(clientName, dataSource, builder);
        }
        return builder.build();
    }

    private static DataSource buildSelfManagedDataSource(
            String propertyPath,
            JoyaDataSourceProperties props) {
        DataSource dataSource = props.initializeDataSourceBuilder().build();
        Map<String, Object> hikariProps = props.getHikari();
        if (hikariProps != null && !hikariProps.isEmpty()) {
            try {
                org.springframework.boot.context.properties.bind.Binder binder =
                        new org.springframework.boot.context.properties.bind.Binder(
                                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(hikariProps));
                binder.bind(
                        org.springframework.boot.context.properties.source.ConfigurationPropertyName.EMPTY,
                        org.springframework.boot.context.properties.bind.Bindable.ofInstance(dataSource));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to bind '" + propertyPath + ".hikari' properties onto DataSource of type "
                                + dataSource.getClass().getName(), e);
            }
        }
        return dataSource;
    }

    private static void validateSelfManagedDataSource(
            String propertyPath,
            JoyaDataSourceProperties props) {
        List<String> unsupportedProperties = new ArrayList<>();
        if (isNotBlank(props.getJndiName())) {
            unsupportedProperties.add(propertyPath + ".jndi-name");
        }
        DataSourceProperties.Xa xa = props.getXa();
        if (xa != null) {
            if (isNotBlank(xa.getDataSourceClassName())) {
                unsupportedProperties.add(propertyPath + ".xa.data-source-class-name");
            }
            if (xa.getProperties() != null && !xa.getProperties().isEmpty()) {
                unsupportedProperties.add(propertyPath + ".xa.properties");
            }
        }
        if (!unsupportedProperties.isEmpty()) {
            throw new IllegalStateException(
                    "Unsupported self-managed Joya DataSource configuration: "
                            + String.join(", ", unsupportedProperties)
                            + ". JNDI and XA are not implemented for DataSources created by Joya; "
                            + "remove these properties and configure JDBC 'url'/'type' instead. "
                            + "To reuse a Spring-managed primary DataSource, set 'joya.primary.use-spring=true'.");
        }
    }

    private static void closeManagedResourcesAfterFailure(
            List<AutoCloseable> managedResources,
            Throwable failure) {
        for (int index = managedResources.size() - 1; index >= 0; index--) {
            try {
                managedResources.get(index).close();
            } catch (Throwable closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
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

    private static DataSource resolvePrimaryDataSource(
            String primaryName,
            ObjectProvider<DataSource> springDataSourceProvider,
            ConfigurableListableBeanFactory beanFactory) {
        DataSource ifUnique = springDataSourceProvider.getIfUnique();
        if (ifUnique != null) {
            return ifUnique;
        }
        String[] dsBeanNames = beanFactory.getBeanNamesForType(DataSource.class);
        if (dsBeanNames.length == 0) {
            throw new IllegalStateException(
                    "joya.primary.use-spring is true, but no Spring DataSource bean was found. "
                            + "Configure spring.datasource.* or set joya.primary.use-spring=false with joya.primary.url.");
        }
        for (String candidateName : List.of(primaryName, "dataSource")) {
            for (String beanName : dsBeanNames) {
                if (beanName.equals(candidateName)) {
                    return beanFactory.getBean(beanName, DataSource.class);
                }
            }
        }
        throw new IllegalStateException(
                "joya.primary.use-spring is true, but multiple Spring DataSource beans were found ("
                        + String.join(", ", dsBeanNames)
                        + ") without a @Primary bean or a bean named '" + primaryName + "' / 'dataSource'. "
                        + "Mark your primary DataSource with @Primary or set joya.primary.use-spring=false.");
    }

    /**
     * 解析主库关联的 Spring 事务管理器：仅复用资源工厂直接匹配主 {@link DataSource} 的 {@link ResourceTransactionManager}
     * （如 {@link DataSourceTransactionManager} / {@code JdbcTransactionManager} 体系），以确保 {@code DataSourceUtils}
     * 连接同步契约成立；若容器中仅存在非 {@link DataSource} 资源工厂的事务管理器（如 {@code JpaTransactionManager}），
     * 则记录告警并为 Joya 主客户端创建独立的 {@link DataSourceTransactionManager}。
     */
    private static PlatformTransactionManager resolvePrimaryTransactionManager(
            DataSource primaryDataSource,
            ObjectProvider<PlatformTransactionManager> txManagerProvider) {
        for (PlatformTransactionManager tm : txManagerProvider) {
            if (tm instanceof ResourceTransactionManager rtm
                    && SpringTxEngine.matchesDataSource(rtm.getResourceFactory(), primaryDataSource)) {
                return tm;
            }
        }
        PlatformTransactionManager ifUnique = txManagerProvider.getIfUnique();
        if (ifUnique != null) {
            log.warn("Found Spring PlatformTransactionManager [{}] whose resourceFactory does not match primary DataSource; "
                    + "Joya @Transactional connection sharing only supports DataSourceTransactionManager-based managers. "
                    + "Creating a dedicated DataSourceTransactionManager for the primary JoyaClient.",
                    ifUnique.getClass().getName());
        }
        return new DataSourceTransactionManager(primaryDataSource);
    }

    /**
     * 收集对指定客户端生效的扩展点 Bean：无 {@code @Qualifier} 的全局 Bean + 限定为 {@code clientName} 的 Bean。
     */
    private static <T> List<T> resolveForClient(
            ConfigurableListableBeanFactory beanFactory,
            Class<T> type,
            String clientName) {
        String[] beanNames = beanFactory.getBeanNamesForType(type, false, false);
        if (beanNames.length == 0) {
            return List.of();
        }
        List<T> matched = new ArrayList<>();
        for (String beanName : beanNames) {
            Set<String> qualifiers = extractQualifierValues(beanFactory, beanName);
            if (qualifiers.isEmpty() || qualifiers.contains(clientName)) {
                matched.add(resolveExtensionBean(beanFactory, beanName, type));
            }
        }
        return matched;
    }

    @SuppressWarnings("unchecked")
    private static <T> T resolveExtensionBean(
            ConfigurableListableBeanFactory beanFactory,
            String beanName,
            Class<T> type) {
        try {
            return beanFactory.getBean(beanName, type);
        } catch (RuntimeException ex) {
            if (!isCurrentlyInCreationFailure(ex) || !type.isInterface()) {
                throw ex;
            }
            return (T) Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[]{type},
                    new java.lang.reflect.InvocationHandler() {
                        private volatile T delegate;

                        private T target() {
                            T resolved = delegate;
                            if (resolved == null) {
                                synchronized (this) {
                                    resolved = delegate;
                                    if (resolved == null) {
                                        resolved = beanFactory.getBean(beanName, type);
                                        delegate = resolved;
                                    }
                                }
                            }
                            return resolved;
                        }

                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            String name = method.getName();
                            if ("order".equals(name) && (args == null || args.length == 0) && delegate == null) {
                                return 0;
                            }
                            if ("equals".equals(name)) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("toString".equals(name)) {
                                return "LazyJoyaExtensionProxy[" + beanName + "]";
                            }
                            try {
                                return method.invoke(target(), args);
                            } catch (InvocationTargetException ite) {
                                throw ite.getTargetException();
                            }
                        }
                    });
        }
    }

    private static boolean isCurrentlyInCreationFailure(Throwable ex) {
        for (Throwable cur = ex; cur != null; cur = cur.getCause()) {
            if (cur instanceof BeanCurrentlyInCreationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析单例型扩展点（如 {@code RowMapperFactory}、{@code SqlExceptionTranslator}）：
     * 限定为当前客户端的 Bean 优先，否则取全局 Bean；同一级别出现多个候选时显式报错，避免静默二选一。
     */
    private static <T> T resolveSingleForClient(
            ConfigurableListableBeanFactory beanFactory,
            Class<T> type,
            String clientName) {
        List<String> scoped = new ArrayList<>();
        List<String> global = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForType(type, false, false)) {
            Set<String> qualifiers = extractQualifierValues(beanFactory, beanName);
            if (qualifiers.contains(clientName)) {
                scoped.add(beanName);
            } else if (qualifiers.isEmpty()) {
                global.add(beanName);
            }
        }
        List<String> candidates = scoped.isEmpty() ? global : scoped;
        if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple " + type.getSimpleName() + " beans match Joya client '"
                    + clientName + "': " + candidates + ". Keep exactly one global bean, or qualify one per client "
                    + "with @Qualifier(\"" + clientName + "\").");
        }
        return candidates.isEmpty() ? null : beanFactory.getBean(candidates.get(0), type);
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
