package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.naming.TableNameResolver;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.spring.support.JpaTransactionManagerSupport;
import com.sondertara.joya.spring.support.SpringConnectionProvider;
import com.sondertara.joya.spring.support.SpringSqlExceptionTranslator;
import com.sondertara.joya.spring.support.SpringTxEngine;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装 {@link JoyaClientRegistry}：主库 + {@code joya.datasources} 扩展库走完全相同的客户端构建流程。
 */
final class JoyaClientAssembler {

    private static final Logger log = LoggerFactory.getLogger(JoyaClientAssembler.class);

    private final JoyaProperties properties;
    private final @Nullable Environment environment;
    private final ObjectProvider<DataSource> springDataSourceProvider;
    private final ObjectProvider<PlatformTransactionManager> txManagerProvider;
    private final ConfigurableListableBeanFactory beanFactory;
    private final JoyaExtensionPointResolver extensionResolver;

    JoyaClientAssembler(JoyaProperties properties,
                        @Nullable Environment environment,
                        ObjectProvider<DataSource> springDataSourceProvider,
                        ObjectProvider<PlatformTransactionManager> txManagerProvider,
                        ConfigurableListableBeanFactory beanFactory) {
        this.properties = properties;
        this.environment = environment;
        this.springDataSourceProvider = springDataSourceProvider;
        this.txManagerProvider = txManagerProvider;
        this.beanFactory = beanFactory;
        this.extensionResolver = new JoyaExtensionPointResolver(beanFactory);
    }

    JoyaClientRegistry assemble(List<JoyaClientCustomizer> customizers) {
        Map<String, JoyaClient> clients = new LinkedHashMap<>();
        Map<String, DataSource> sidecarDataSources = new LinkedHashMap<>();
        List<AutoCloseable> managedResources = new ArrayList<>();

        try {
            PrimaryDataSourceProperties primaryProps = properties.getPrimary();
            String primaryName = Strings.isBlank(primaryProps.getName())
                    ? JoyaClientRegistry.PRIMARY
                    : primaryProps.getName().trim();

            DataSource primaryDataSource;
            PlatformTransactionManager primaryTxManager;
            if (primaryProps.isUseSpring()) {
                if (Strings.isNotBlank(primaryProps.getDataSourceBean())) {
                    JoyaDataSourceFactory.validateBeanReference("joya.primary", primaryProps, environment);
                    primaryDataSource = resolveDataSourceBean("joya.primary", primaryProps.getDataSourceBean());
                    primaryTxManager = resolveExplicitOrOptionalTxManager(
                            "joya.primary", primaryProps, primaryDataSource, false);
                } else {
                    if (JoyaDataSourceFactory.hasCustomConnectionConfig(primaryProps, environment)) {
                        log.warn("[Joya] 'joya.primary.use-spring' is true: custom connection/pool properties under 'joya.primary' "
                                + "(url/username/password/driver-class-name/type/hikari) will be ignored and Spring's native DataSource will be used instead. "
                                + "Set 'joya.primary.use-spring=false' if you want to use the custom primary datasource.");
                    }
                    primaryDataSource = JoyaDataSourceFactory.resolvePrimary(
                            primaryName, springDataSourceProvider, beanFactory);
                    primaryTxManager = Strings.isNotBlank(primaryProps.getTransactionManagerBean())
                            ? resolveExplicitTxManager("joya.primary", primaryProps.getTransactionManagerBean(), primaryDataSource)
                            : JoyaTransactionManagerResolver.resolve(primaryDataSource, txManagerProvider);
                }
            } else if (Strings.isNotBlank(primaryProps.getDataSourceBean())) {
                JoyaDataSourceFactory.validateBeanReference("joya.primary", primaryProps, environment);
                primaryDataSource = resolveDataSourceBean("joya.primary", primaryProps.getDataSourceBean());
                primaryTxManager = resolveExplicitOrOptionalTxManager(
                        "joya.primary", primaryProps, primaryDataSource, true);
            } else {
                if (Strings.isNotBlank(primaryProps.getTransactionManagerBean())) {
                    throw new IllegalStateException(
                            "Conflicting Joya DataSource configuration under 'joya.primary': "
                                    + "'transaction-manager-bean' cannot be used with self-managed primary DataSource; "
                                    + "set 'use-spring=true' or specify 'data-source-bean'.");
                }
                JoyaDataSourceFactory.validateSelfManaged("joya.primary", primaryProps, environment);
                primaryDataSource = JoyaDataSourceFactory.buildSelfManaged("joya.primary", primaryProps);
                if (primaryDataSource instanceof AutoCloseable closeable) {
                    managedResources.add(closeable);
                }
                sidecarDataSources.put(primaryName, primaryDataSource);
                primaryTxManager = null;
            }

            clients.put(primaryName, buildClient(primaryName, primaryProps, primaryDataSource,
                    primaryTxManager, customizers));

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
                String propertyPath = "joya.datasources[" + index + "]";
                DataSource extraDataSource;
                PlatformTransactionManager extraTxManager;
                if (Strings.isNotBlank(dsProps.getDataSourceBean())) {
                    JoyaDataSourceFactory.validateBeanReference(propertyPath, dsProps, environment);
                    extraDataSource = resolveDataSourceBean(propertyPath, dsProps.getDataSourceBean());
                    extraTxManager = resolveExplicitOrOptionalTxManager(
                            propertyPath, dsProps, extraDataSource, true);
                } else {
                    if (Strings.isNotBlank(dsProps.getTransactionManagerBean())) {
                        throw new IllegalStateException(
                                "Conflicting Joya DataSource configuration under '" + propertyPath + "': "
                                        + "'transaction-manager-bean' requires 'data-source-bean' to be specified.");
                    }
                    JoyaDataSourceFactory.validateSelfManaged(propertyPath, dsProps, environment);
                    extraDataSource = JoyaDataSourceFactory.buildSelfManaged(propertyPath, dsProps);
                    if (extraDataSource instanceof AutoCloseable closeable) {
                        managedResources.add(closeable);
                    }
                    sidecarDataSources.put(name, extraDataSource);
                    extraTxManager = null;
                }
                clients.put(name, buildClient(name, dsProps, extraDataSource, extraTxManager, customizers));
            }

            return new JoyaClientRegistry(primaryName, clients, managedResources, sidecarDataSources);
        } catch (RuntimeException | Error failure) {
            closeManagedResourcesAfterFailure(managedResources, failure);
            throw failure;
        }
    }

    private DataSource resolveDataSourceBean(String propertyPath, String beanName) {
        String trimmed = beanName.trim();
        if (!beanFactory.containsBean(trimmed)) {
            throw new IllegalStateException("Configured '" + propertyPath + ".data-source-bean' ('"
                    + trimmed + "') was not found in the Spring application context.");
        }
        try {
            return beanFactory.getBean(trimmed, DataSource.class);
        } catch (BeansException ex) {
            throw new IllegalStateException("Configured '" + propertyPath + ".data-source-bean' ('"
                    + trimmed + "') could not be resolved as a javax.sql.DataSource.", ex);
        }
    }

    private PlatformTransactionManager resolveExplicitOrOptionalTxManager(String propertyPath,
                                                                         JoyaDataSourceProperties props,
                                                                         DataSource targetDataSource,
                                                                         boolean optionalMatch) {
        if (Strings.isNotBlank(props.getTransactionManagerBean())) {
            return resolveExplicitTxManager(propertyPath, props.getTransactionManagerBean(), targetDataSource);
        }
        return optionalMatch
                ? JoyaTransactionManagerResolver.resolveOptional(targetDataSource, txManagerProvider)
                : JoyaTransactionManagerResolver.resolve(targetDataSource, txManagerProvider);
    }

    private PlatformTransactionManager resolveExplicitTxManager(String propertyPath,
                                                                String beanName,
                                                                DataSource targetDataSource) {
        String trimmed = beanName.trim();
        if (!beanFactory.containsBean(trimmed)) {
            throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                    + trimmed + "') was not found in the Spring application context.");
        }
        PlatformTransactionManager tm;
        try {
            tm = beanFactory.getBean(trimmed, PlatformTransactionManager.class);
        } catch (BeansException ex) {
            throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                    + trimmed + "') could not be resolved as a PlatformTransactionManager.", ex);
        }
        if (JpaTransactionManagerSupport.isJta(tm)) {
            throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                    + trimmed + "') is a JTA transaction manager, which is not supported by Joya.");
        }
        if (tm instanceof ResourceTransactionManager rtm) {
            if (!SpringTxEngine.matchesDataSource(rtm.getResourceFactory(), targetDataSource)) {
                throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                        + trimmed + "') does not manage the target DataSource.");
            }
            return tm;
        }
        if (JpaTransactionManagerSupport.isJpa(tm)) {
            DataSource jpaDs = JpaTransactionManagerSupport.dataSource(tm);
            if (jpaDs == null || !SpringTxEngine.matchesDataSource(jpaDs, targetDataSource)) {
                throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                        + trimmed + "') is a JpaTransactionManager not bound to the target DataSource.");
            }
            return tm;
        }
        throw new IllegalStateException("Configured '" + propertyPath + ".transaction-manager-bean' ('"
                + trimmed + "') of type " + tm.getClass().getName()
                + " cannot be verified against the target DataSource.");
    }

    private JoyaClient buildClient(String clientName,
                                   JoyaDataSourceProperties dsProps,
                                   DataSource dataSource,
                                   PlatformTransactionManager txManager,
                                   List<JoyaClientCustomizer> customizers) {
        JoyaSettings settings = JoyaSettingsFactory.settings(properties, dsProps);
        TableNameResolver tableNameResolver =
                extensionResolver.singleForClient(TableNameResolver.class, clientName);
        if (tableNameResolver != null) {
            settings = settings.withTableNameResolver(tableNameResolver);
        }
        if ((dsProps != null && dsProps.getDialect() != null && Boolean.TRUE.equals(dsProps.getDynamicDialect()))
                || (properties.getDialect() != null && properties.isDynamicDialect())) {
            log.warn("[Joya] Both 'dialect' and 'dynamic-dialect=true' are configured on datasource '{}'; "
                    + "'dynamic-dialect' takes precedence and 'dialect' will be ignored.", clientName);
        }
        DialectStrategy dialectStrategy = JoyaSettingsFactory.dialectStrategy(properties, dsProps, dataSource);

        // 扩展点按类型 + @Qualifier(clientName) 路由：无限定符的 Bean 对所有客户端生效，带限定符的只对同名客户端生效
        JoyaClient.Builder builder = JoyaClient.builder(dataSource)
                .clientName(clientName)
                .settings(settings)
                .dialectStrategy(dialectStrategy)
                .rewriters(extensionResolver.forClient(SqlRewriter.class, clientName))
                .executionListeners(extensionResolver.forClient(SqlExecutionListener.class, clientName));
        Executor.Factory executorFactory =
                extensionResolver.singleForClient(Executor.Factory.class, clientName);
        if (executorFactory != null) {
            builder.executorFactory(executorFactory);
        }
        RowMapperFactory rowMapperFactory =
                extensionResolver.singleForClient(RowMapperFactory.class, clientName);
        if (rowMapperFactory != null) {
            builder.rowMapperFactory(rowMapperFactory);
        }
        SqlExceptionTranslator exceptionTranslator =
                extensionResolver.singleForClient(SqlExceptionTranslator.class, clientName);
        builder.exceptionTranslator(exceptionTranslator != null
                ? exceptionTranslator
                : new SpringSqlExceptionTranslator(dataSource));

        if (txManager != null) {
            // 初始传 0，由 JoyaClient.build() 按 JoyaClientCustomizer 执行后的最终 JoyaSettings 下沉默认查询超时
            SpringConnectionProvider connectionProvider = new SpringConnectionProvider(dataSource, 0);
            SpringTxEngine txEngine = new SpringTxEngine(dataSource, txManager);
            builder.connectionProvider(connectionProvider).txEngine(txEngine);
        }

        for (JoyaClientCustomizer customizer : customizers) {
            customizer.customize(clientName, dataSource, builder);
        }
        return builder.build();
    }

    private static void closeManagedResourcesAfterFailure(List<AutoCloseable> managedResources, Throwable failure) {
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
}
