package com.sondertara.joya.datasource;

import lombok.Data;

import javax.sql.DataSource;
import java.io.Serializable;
import java.util.Properties;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/30 14:09
 */
@Data
public class DataSourceProperties implements Serializable {

    protected String name = "default";
    protected ClassLoader classLoader;
    protected Class<? extends DataSource> type;
    protected String url;
    protected String driverClassName;
    protected String username;
    protected String password;
    /**
     * 连接超时 unit:second
     */
    protected int connectionTimeout = 60;
    protected String connectionTestQuery;

    protected String connectionInitSql;

    /**
     * 验证连接超时时间 unit:second
     */
    protected int validationTimeout = 60;

    /**
     * 空闲连接超时时间 unit:second
     */
    protected int idleTimeout = 60;

    /**
     * 最大存活时间 5分钟 unit:second
     */
    protected long maxLifetime = 300;

    /**
     * 最大连接池数量
     */
    protected int maxPoolSize = 100;

    /**
     * 最小空闲数量
     */
    protected int minIdleSize = 10;

    protected int initialSize = 2;

    /**
     * 连接池名称
     */
    protected String poolName = "Joya-DS";

    protected Properties jdbcProperties = new Properties();
}
