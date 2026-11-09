package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.spring.support.SpringConnectionProvider;
import com.sondertara.joya.spring.support.SpringTxEngine;
import com.sondertara.joya.utils.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

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
    private final ObjectProvider<DataSource> springDataSourceProvider;
    private final ObjectProvider<PlatformTransactionManager> txManagerProvider;
    private final ConfigurableListableBeanFactory beanFactory;
    private final JoyaExtensionPointResolver extensionResolver;

    JoyaClientAssembler(JoyaProperties properties,
                        ObjectProvider<DataSource> springDataSourceProvider,
                        ObjectProvider<PlatformTransactionManager> txManagerProvider,
                        ConfigurableListableBeanFactory beanFactory) {
        this.properties = properties;
        this.springDataSourceProvider = springDataSourceProvider;
        this.txManagerProvider = txManagerProvider;
        this.beanFactory = beanFactory;
        this.extensionResolver = new JoyaExtensionPointResolver(beanFactory);
    }

    JoyaClientRegistry assemble(List<JoyaClientCustomizer> customizers) {
        Map<String, JoyaClient> clients = new LinkedHashMap<>();
        List<AutoCloseable> managedResources = new ArrayList<>();

        try {
            PrimaryDataSourceProperties primaryProps = properties.getPrimary();
            String primaryName = Strings.isBlank(primaryProps.getName())
                    ? JoyaClientRegistry.PRIMARY
                    : primaryProps.getName().trim();

            DataSource primaryDataSource;
            PlatformTransactionManager primaryTxManager;
            if (primaryProps.isUseSpring()) {
                if (JoyaDataSourceFactory.hasCustomConnectionConfig(primaryProps)) {
                    log.warn("[Joya] 'joya.primary.use-spring' is true: custom connection properties under 'joya.primary' "
                            + "(url/username/password/driver-class-name) will be ignored and Spring's native DataSource will be used instead. "
                            + "Set 'joya.primary.use-spring=false' if you want to use the custom primary datasource.");
                }
                primaryDataSource = JoyaDataSourceFactory.resolvePrimary(
                        primaryName, springDataSourceProvider, beanFactory);
                primaryTxManager = JoyaTransactionManagerResolver.resolve(primaryDataSource, txManagerProvider);
            } else {
                JoyaDataSourceFactory.validateSelfManaged("joya.primary", primaryProps);
                primaryDataSource = JoyaDataSourceFactory.buildSelfManaged("joya.primary", primaryProps);
                if (primaryDataSource instanceof AutoCloseable closeable) {
                    managedResources.add(closeable);
                }
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
                JoyaDataSourceFactory.validateSelfManaged(propertyPath, dsProps);
                DataSource extraDataSource = JoyaDataSourceFactory.buildSelfManaged(propertyPath, dsProps);
                if (extraDataSource instanceof AutoCloseable closeable) {
                    managedResources.add(closeable);
                }
                clients.put(name, buildClient(name, dsProps, extraDataSource, null, customizers));
            }

            return new JoyaClientRegistry(primaryName, clients, managedResources);
        } catch (RuntimeException | Error failure) {
            closeManagedResourcesAfterFailure(managedResources, failure);
            throw failure;
        }
    }

    private JoyaClient buildClient(String clientName,
                                   JoyaDataSourceProperties dsProps,
                                   DataSource dataSource,
                                   PlatformTransactionManager txManager,
                                   List<JoyaClientCustomizer> customizers) {
        JoyaSettings settings = JoyaSettingsFactory.settings(properties, dsProps);
        if ((dsProps != null && dsProps.getDialect() != null && Boolean.TRUE.equals(dsProps.getDynamicDialect()))
                || (properties.getDialect() != null && properties.isDynamicDialect())) {
            log.warn("[Joya] Both 'dialect' and 'dynamic-dialect=true' are configured on datasource '{}'; "
                    + "'dynamic-dialect' takes precedence and 'dialect' will be ignored.", clientName);
        }
        DialectStrategy dialectStrategy = JoyaSettingsFactory.dialectStrategy(properties, dsProps, dataSource);

        // 扩展点按类型 + @Qualifier(clientName) 路由：无限定符的 Bean 对所有客户端生效，带限定符的只对同名客户端生效
        JoyaClient.Builder builder = JoyaClient.builder(dataSource)
                .settings(settings)
                .dialectStrategy(dialectStrategy)
                .rewriters(extensionResolver.forClient(SqlRewriter.class, clientName))
                .executionListeners(extensionResolver.forClient(SqlExecutionListener.class, clientName))
                .entityListeners(extensionResolver.forClient(EntityListener.class, clientName));
        RowMapperFactory rowMapperFactory =
                extensionResolver.singleForClient(RowMapperFactory.class, clientName);
        if (rowMapperFactory != null) {
            builder.rowMapperFactory(rowMapperFactory);
        }
        SqlExceptionTranslator exceptionTranslator =
                extensionResolver.singleForClient(SqlExceptionTranslator.class, clientName);
        if (exceptionTranslator != null) {
            builder.exceptionTranslator(exceptionTranslator);
        }

        if (txManager != null) {
            SpringConnectionProvider connectionProvider = new SpringConnectionProvider(
                    dataSource, settings.executionOptions().queryTimeoutSeconds());
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
