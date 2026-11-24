package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自建 / Spring 复用数据源的解析与校验（{@link JoyaAutoConfiguration} 的构造细节）。
 */
final class JoyaDataSourceFactory {

    private JoyaDataSourceFactory() {
    }

    /**
     * 解析主库数据源：优先唯一 Bean / {@code @Primary}，其次按名称（primaryName / dataSource）匹配。
     */
    static DataSource resolvePrimary(String primaryName,
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
                            + "Configure spring.datasource.* (with 'spring-boot-starter-jdbc' or a connection pool on the classpath) "
                            + "or set joya.primary.use-spring=false with joya.primary.url.");
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

    static DataSource buildSelfManaged(String propertyPath, JoyaDataSourceProperties props) {
        return buildSelfManaged(propertyPath, props != null ? props.getName() : null, props);
    }

    /**
     * 按配置构造独立数据源，在首次建连前预设默认 Hikari 池名（{@code joya-<name>}），
     * 并把 {@code hikari} 子属性严格绑定到实例上（未知或拼写错误的属性立即失败）。
     */
    static DataSource buildSelfManaged(String propertyPath,
                                       @Nullable String clientName,
                                       JoyaDataSourceProperties props) {
        if (Strings.isBlank(props.getUrl())) {
            throw new IllegalStateException(
                    "Failed to create self-managed Joya DataSource under '" + propertyPath
                            + "': '" + propertyPath + ".url' must be specified (or configure '"
                            + propertyPath + ".data-source-bean' to reuse an existing Spring DataSource bean).");
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = JoyaDataSourceFactory.class.getClassLoader();
        }
        if (props.getType() == null && DataSourceBuilder.findType(classLoader) == null) {
            throw new IllegalStateException(
                    "Failed to create self-managed Joya DataSource under '" + propertyPath
                            + "': no supported connection pool implementation (such as HikariCP) was found on the classpath. "
                            + "Ensure 'com.zaxxer:HikariCP' (provided by 'spring-boot-starter-jdbc') is on the classpath, "
                            + "or explicitly configure '" + propertyPath + ".type'.");
        }
        String url = props.getUrl().trim();
        DataSourceBuilder<?> builder = DataSourceBuilder.create(classLoader).url(url);
        if (props.getType() != null) {
            builder.type(props.getType());
        }
        if (Strings.isNotBlank(props.getDriverClassName())) {
            builder.driverClassName(props.getDriverClassName().trim());
        } else {
            String deducedDriver = DatabaseDriver.fromJdbcUrl(url).getDriverClassName();
            if (Strings.isNotBlank(deducedDriver)) {
                builder.driverClassName(deducedDriver);
            }
        }
        if (props.getUsername() != null) {
            builder.username(props.getUsername());
        }
        if (props.getPassword() != null) {
            builder.password(props.getPassword());
        }
        DataSource dataSource = builder.build();
        Map<String, Object> hikariProps = props.getHikari();
        boolean hasExplicitPoolName = hasExplicitHikariPoolName(hikariProps);
        if (!hasExplicitPoolName
                && Strings.isNotBlank(clientName)
                && "com.zaxxer.hikari.HikariDataSource".equals(dataSource.getClass().getName())) {
            // 在首次建连封板前即确立清晰可观测的默认连接池名，避免早于 Actuator 初始化建连后退化为 HikariPool-N
            new Binder(new MapConfigurationPropertySource(Map.of("pool-name", "joya-" + clientName.trim())))
                    .bind(ConfigurationPropertyName.EMPTY, Bindable.ofInstance(dataSource));
        }
        if (hikariProps != null && !hikariProps.isEmpty()) {
            try {
                MapConfigurationPropertySource source = new MapConfigurationPropertySource(hikariProps);
                Binder binder = new Binder(source);
                BindHandler strictHandler = new NoUnboundElementsBindHandler(
                        BindHandler.DEFAULT,
                        cp -> cp == source);
                binder.bind(ConfigurationPropertyName.EMPTY, Bindable.ofInstance(dataSource), strictHandler);
            } catch (Throwable t) {
                Throwable failure = (t instanceof Error)
                        ? t
                        : new IllegalStateException(
                                "Failed to bind '" + propertyPath + ".hikari' properties onto DataSource of type "
                                        + dataSource.getClass().getName(), t);
                if (dataSource instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Throwable closeErr) {
                        failure.addSuppressed(closeErr);
                    }
                }
                if (failure instanceof Error err) {
                    throw err;
                }
                throw (RuntimeException) failure;
            }
        }
        return dataSource;
    }

    private static boolean hasExplicitHikariPoolName(@Nullable Map<String, Object> hikariProps) {
        if (hikariProps == null || hikariProps.isEmpty()) {
            return false;
        }
        for (String key : hikariProps.keySet()) {
            if (key == null) {
                continue;
            }
            String normalized = key.replace("-", "").replace("_", "");
            if ("poolname".equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 明确拒绝 Joya 未实现的 JNDI / XA 配置与空 URL，避免静默忽略或底层 NPE。
     */
    static void validateSelfManaged(String propertyPath,
                                    JoyaDataSourceProperties props,
                                    @Nullable Environment environment) {
        List<String> unsupportedProperties = collectUnsupportedJndiOrXa(propertyPath, environment);
        if (!unsupportedProperties.isEmpty()) {
            throw new IllegalStateException(
                    "Unsupported self-managed Joya DataSource configuration: "
                            + String.join(", ", unsupportedProperties)
                            + ". JNDI and XA are not implemented for DataSources created by Joya; "
                            + "remove these properties and configure JDBC 'url'/'type' instead. "
                            + "To reuse a Spring-managed primary DataSource, set 'joya.primary.use-spring=true'.");
        }
        if (Strings.isBlank(props.getUrl())) {
            throw new IllegalStateException(
                    "Failed to create self-managed Joya DataSource under '" + propertyPath
                            + "': '" + propertyPath + ".url' must be specified (or configure '"
                            + propertyPath + ".data-source-bean' to reuse an existing Spring DataSource bean).");
        }
    }

    /**
     * 校验复用容器既有 {@link DataSource} Bean 时不得同时配置自建连接/连接池属性，避免语义歧义。
     */
    static void validateBeanReference(String propertyPath,
                                      JoyaDataSourceProperties props,
                                      @Nullable Environment environment) {
        List<String> conflicts = new ArrayList<>();
        if (Strings.isNotBlank(props.getUrl())) {
            conflicts.add(propertyPath + ".url");
        }
        if (Strings.isNotBlank(props.getUsername())) {
            conflicts.add(propertyPath + ".username");
        }
        if (Strings.isNotBlank(props.getPassword())) {
            conflicts.add(propertyPath + ".password");
        }
        if (Strings.isNotBlank(props.getDriverClassName())) {
            conflicts.add(propertyPath + ".driver-class-name");
        }
        if (props.getType() != null) {
            conflicts.add(propertyPath + ".type");
        }
        if (props.getHikari() != null && !props.getHikari().isEmpty()) {
            conflicts.add(propertyPath + ".hikari");
        }
        if (Boolean.TRUE.equals(props.getExposeTransactionManager())) {
            conflicts.add(propertyPath + ".expose-transaction-manager");
        }
        conflicts.addAll(collectUnsupportedJndiOrXa(propertyPath, environment));
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                    "Conflicting Joya DataSource configuration under '" + propertyPath + "': "
                            + "'data-source-bean' cannot be combined with connection/pool properties ("
                            + String.join(", ", conflicts) + ").");
        }
    }

    static boolean hasCustomConnectionConfig(PrimaryDataSourceProperties props, @Nullable Environment environment) {
        return Strings.isNotBlank(props.getUrl())
                || Strings.isNotBlank(props.getUsername())
                || Strings.isNotBlank(props.getPassword())
                || Strings.isNotBlank(props.getDriverClassName())
                || props.getType() != null
                || (props.getHikari() != null && !props.getHikari().isEmpty())
                || !collectUnsupportedJndiOrXa("joya.primary", environment).isEmpty();
    }

    private static List<String> collectUnsupportedJndiOrXa(String propertyPath, @Nullable Environment environment) {
        if (environment == null) {
            return List.of();
        }
        Binder binder = Binder.get(environment);
        List<String> found = new ArrayList<>();
        String jndi = binder.bind(propertyPath + ".jndi-name", String.class).orElse(null);
        if (Strings.isNotBlank(jndi)) {
            found.add(propertyPath + ".jndi-name");
        }
        String xaClass = binder.bind(propertyPath + ".xa.data-source-class-name", String.class).orElse(null);
        if (Strings.isNotBlank(xaClass)) {
            found.add(propertyPath + ".xa.data-source-class-name");
        }
        Map<?, ?> xaProps = binder.bind(propertyPath + ".xa.properties", Map.class).orElse(null);
        if (xaProps != null && !xaProps.isEmpty()) {
            found.add(propertyPath + ".xa.properties");
        }
        return found;
    }
}
