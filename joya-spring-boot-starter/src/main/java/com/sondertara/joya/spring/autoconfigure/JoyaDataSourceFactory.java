package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.PrimaryDataSourceProperties;
import com.sondertara.joya.utils.Strings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

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
     * 按配置构造独立数据源，并把 {@code hikari} 子属性绑定到实例上。
     */
    static DataSource buildSelfManaged(String propertyPath, JoyaDataSourceProperties props) {
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

    /**
     * 明确拒绝 Joya 未实现的 JNDI / XA 配置，避免静默降级。
     */
    static void validateSelfManaged(String propertyPath, JoyaDataSourceProperties props) {
        List<String> unsupportedProperties = new ArrayList<>();
        if (Strings.isNotBlank(props.getJndiName())) {
            unsupportedProperties.add(propertyPath + ".jndi-name");
        }
        DataSourceProperties.Xa xa = props.getXa();
        if (xa != null) {
            if (Strings.isNotBlank(xa.getDataSourceClassName())) {
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

    static boolean hasCustomConnectionConfig(PrimaryDataSourceProperties props) {
        return Strings.isNotBlank(props.getUrl())
                || Strings.isNotBlank(props.getUsername())
                || Strings.isNotBlank(props.getPassword())
                || Strings.isNotBlank(props.getDriverClassName())
                || Strings.isNotBlank(props.getJndiName());
    }
}
