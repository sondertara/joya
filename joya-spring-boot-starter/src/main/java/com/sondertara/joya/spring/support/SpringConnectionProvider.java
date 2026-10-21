package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Spring 环境连接提供者：通过 {@link DataSourceUtils} 获取与释放连接。
 *
 * <ul>
 *   <li>当前线程存在针对该 {@link DataSource} 的 Spring 事务（{@code @Transactional} 或 {@code TransactionTemplate}）时，
 *       直接复用线程绑定的事务连接，且 {@link #release(Connection)} 不会关闭连接。</li>
 *   <li>当前线程无事务时，从连接池借出连接并在语句执行完毕后立即归还。</li>
 * </ul>
 */
public final class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;
    private final int defaultTimeoutSeconds;

    public SpringConnectionProvider(DataSource dataSource, int defaultTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.defaultTimeoutSeconds = Math.max(0, defaultTimeoutSeconds);
    }

    @Override
    public Connection acquire() {
        return DataSourceUtils.getConnection(dataSource);
    }

    @Override
    public void release(Connection connection) {
        if (connection != null) {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (resource instanceof ConnectionHolder holder && holder.hasTimeout()) {
            return OptionalInt.of(Math.max(1, holder.getTimeToLiveInSeconds()));
        }
        return defaultTimeoutSeconds > 0 ? OptionalInt.of(defaultTimeoutSeconds) : OptionalInt.empty();
    }

    public DataSource dataSource() {
        return dataSource;
    }
}
