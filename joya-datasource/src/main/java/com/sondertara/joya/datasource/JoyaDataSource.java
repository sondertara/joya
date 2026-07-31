package com.sondertara.joya.datasource;


import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalConnectionPoolConfigurationSupplier;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * zh 构建连接池示例模板
 *
 * @author: ZhangHe
 * @since: 2020/10/31 13:50
 */
@Slf4j
public class JoyaDataSource extends DataSourceProperties implements DataSource, Closeable {

    private volatile AgroalDataSource rawDataSource;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    private volatile int initFlag = 0;

    public JoyaDataSource() {
    }

    public JoyaDataSource(final DataSourceProperties dataSourceProperties) {
        this.name = dataSourceProperties.getName();
        this.classLoader = dataSourceProperties.getClassLoader();
        this.type = dataSourceProperties.getType();
        this.url = dataSourceProperties.getUrl();
        this.driverClassName = dataSourceProperties.getDriverClassName();
        this.username = dataSourceProperties.getUsername();
        this.password = dataSourceProperties.getPassword();
        this.connectionTimeout = dataSourceProperties.getConnectionTimeout();
        this.connectionTestQuery = dataSourceProperties.getConnectionTestQuery();
        this.connectionInitSql = dataSourceProperties.getConnectionInitSql();
        this.validationTimeout = dataSourceProperties.getValidationTimeout();
        this.idleTimeout = dataSourceProperties.getIdleTimeout();
        this.maxLifetime = dataSourceProperties.getMaxLifetime();
        this.maxPoolSize = dataSourceProperties.getMaxPoolSize();
        this.minIdleSize = dataSourceProperties.getMinIdleSize();
        this.poolName = dataSourceProperties.getPoolName();
        this.jdbcProperties = dataSourceProperties.getJdbcProperties();
        this.initialSize = dataSourceProperties.getInitialSize();
        init();

    }

    public AgroalDataSource getInstance() {
        if (this.initFlag == -1) {
            throw new IllegalStateException("No singleton instance has been created.");

        } else if (this.initFlag == 1) {
            return this.rawDataSource;
        } else if (this.initialized.compareAndSet(false, true) && null == this.rawDataSource) {
            try {
                this.init();
                this.initFlag = 1;
            } catch (Exception e) {
                this.initFlag = -1;
                throw new IllegalStateException("Crate singleton instance error," + e.getMessage(), e);
            } finally {
                this.startTimeInitialized.countDown();
            }
        }

        while (0 == this.initFlag) {
            try {
                this.startTimeInitialized.await();
            } catch (InterruptedException var2) {
                Thread.currentThread().interrupt();
            }
        }

        return this.rawDataSource;

    }

    protected void init() {
        ClassLoader classLoader = this.getClassLoader();
        Class<? extends DataSource> type = this.getType();
        String url = this.getUrl();
        String driverClassName = this.getDriverClassName();
        String username = this.getUsername();
        String password = this.getPassword();
        long connectionTimeout = this.getConnectionTimeout();
        String connectionTestQuery = this.getConnectionTestQuery();
        String connectionInitSql = this.getConnectionInitSql();
        int validationTimeout = this.getValidationTimeout();
        long idleTimeout = this.getIdleTimeout();
        long maxLifetime = this.getMaxLifetime();
        int maxPoolSize = this.getMaxPoolSize();
        int initialSize = this.getInitialSize();
        int minIdle = this.getMinIdleSize();
        String poolName = this.getPoolName();

        AgroalConnectionFactoryConfigurationSupplier supplier = new AgroalConnectionFactoryConfigurationSupplier()

                .connectionProviderClassName(driverClassName)
                .initialSql(connectionInitSql)
                .jdbcUrl(url)
                .loginTimeout(Duration.ofSeconds(connectionTimeout))
                .jdbcProperties(this.getJdbcProperties())
                .credential(new SimplePassword(password))
                .principal(new NamePrincipal(username));

        AgroalDataSourceConfiguration configuration = new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(new AgroalConnectionPoolConfigurationSupplier()
                        .connectionFactoryConfiguration(supplier)
                        .name(poolName)
                        .initialSize(initialSize)
                        .reapTimeout(Duration.ofSeconds(idleTimeout))
                        .maxSize(maxPoolSize)
                        .maxLifetime(Duration.ofSeconds(maxLifetime))
                        .idleValidationTimeout(Duration.ofSeconds(validationTimeout))
                        .validationTimeout(Duration.ofSeconds(validationTimeout))
                        .minSize(minIdle)
                        .connectionValidator(connection -> {
                            try {
                                if (connection.isValid(validationTimeout)) {
                                    if (null != connectionTestQuery && connectionTestQuery.length() > 0) {
                                        connection.createStatement().execute(connectionTestQuery);
                                    }
                                }
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
                            return false;
                        }).get())
                .get();
        try {
            this.rawDataSource = AgroalDataSource.from(configuration);
            this.initFlag = 1;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getInstance().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getInstance().getConnection(username, password);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return getInstance().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return getInstance().isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return getInstance().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        getInstance().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        getInstance().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return getInstance().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return getInstance().getParentLogger();
    }

    @Override
    public void close() throws IOException {
        getInstance().close();
    }
}
