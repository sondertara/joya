
package com.sondertara.joya.migration.service;


import com.sondertara.common.spi.ExtensionServiceProvider;
import com.sondertara.joya.datasource.DataSourceFactory;
import com.sondertara.joya.datasource.DataSourceProperties;
import com.sondertara.joya.datasource.DefaultDataSourceFactory;
import com.sondertara.joya.migration.exchange.domain.TargetEndpointProperties;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DataSource工具类
 *
 * @author tang
 */
@Slf4j
@UtilityClass
final class DataSourceUtils {
    static Map<String, DataSourceFactory> DS_FACTORY_MAP = new ConcurrentHashMap<>();

    static {
        Map<String, DataSourceFactory> instances = new ExtensionServiceProvider("META-INF/joya").loadInstances(DataSourceFactory.class);
        DS_FACTORY_MAP.putAll(instances);

    }

    /**
     * 创建于指定数据库连接描述符的连接池
     *
     * @param properties 数据库连接描述符
     * @return HikariDataSource连接池
     */
    public static DataSource createSourceDataSource(
            DataSourceProperties properties) {
        properties.setPoolName("Source-DB");
        Properties parameters = properties.getJdbcProperties();
        if (properties.getDriverClassName().contains("oracle")) {
            properties.setConnectionTestQuery("SELECT 'X' from DUAL");
            // https://blog.csdn.net/qq_20960159/article/details/78593936
            System.getProperties().setProperty("oracle.jdbc.J2EE13Compliant", "true");
            // Oracle在通过jdbc连接的时候需要添加一个参数来设置是否获取注释
            parameters.put("remarksReporting", "true");
        } else if (properties.getDriverClassName().contains("db2")) {
            properties.setConnectionTestQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");
        } else if (properties.getDriverClassName().contains("mongodb")) {
            properties.setConnectionTestQuery("use admin;");
        } else if (!properties.getUrl().contains("jdbc:jest://")) {
            properties.setConnectionTestQuery("SELECT 1");
        }
        DataSourceFactory dataSourceFactory = DS_FACTORY_MAP.getOrDefault(properties.getName(), new DefaultDataSourceFactory());


        return dataSourceFactory.createDataSource(properties);
    }

    /**
     * 创建于指定数据库连接描述符的连接池
     *
     * @param properties 数据库连接描述符
     * @return HikariDataSource连接池
     */
    public static DataSource createTargetDataSource(
            TargetEndpointProperties properties) {
        properties.setPoolName("Target-DB");
        if (properties.getDriverClassName().contains("oracle")) {
            properties.setConnectionTestQuery("SELECT 'X' from DUAL");
        } else if (properties.getDriverClassName().contains("db2")) {
            properties.setConnectionTestQuery("SELECT 1 FROM SYSIBM.SYSDUMMY1");
        } else if (properties.getDriverClassName().contains("mongodb")) {
            properties.setConnectionTestQuery("use admin;");
        } else if (!properties.getUrl().contains("jdbc:jest://")) {
            properties.setConnectionTestQuery("SELECT 1");
        }
        DataSourceFactory dataSourceFactory = DS_FACTORY_MAP.getOrDefault(properties.getName(), new DefaultDataSourceFactory());
        return dataSourceFactory.createDataSource(properties);
    }
}
