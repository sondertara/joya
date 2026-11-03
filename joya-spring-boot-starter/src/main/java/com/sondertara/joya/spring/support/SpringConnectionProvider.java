package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Spring 环境连接提供者：通过 {@link DataSourceUtils} 获取与释放连接。
 *
 * <ul>
 *   <li>当前线程存在针对该 {@link DataSource} 的 Spring 事务（{@code @Transactional} 或 {@code TransactionTemplate}）时，
 *       直接复用线程绑定的事务连接，且 {@link #release(Connection)} 不会关闭连接。</li>
 *   <li>当前线程无事务时，从连接池借出连接并在语句执行完毕后立即归还（若连接池配置了 {@code autoCommit=false}，
 *       借出时临时切为自动提交并在归还前恢复）。</li>
 * </ul>
 */
public final class SpringConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;
    private final int defaultTimeoutSeconds;
    private final Set<Connection> flippedAutoCommit =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public SpringConnectionProvider(DataSource dataSource, int defaultTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.defaultTimeoutSeconds = Math.max(0, defaultTimeoutSeconds);
    }

    @Override
    public Connection acquire() throws SQLException {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            if (!DataSourceUtils.isConnectionTransactional(conn, dataSource) && !conn.getAutoCommit()) {
                conn.setAutoCommit(true);
                flippedAutoCommit.add(conn);
            }
            return conn;
        } catch (SQLException | RuntimeException e) {
            flippedAutoCommit.remove(conn);
            DataSourceUtils.releaseConnection(conn, dataSource);
            throw e;
        }
    }

    @Override
    public void release(Connection connection) throws SQLException {
        if (connection != null) {
            boolean restoreManual = !flippedAutoCommit.isEmpty() && flippedAutoCommit.remove(connection);
            SQLException restoreError = null;
            if (restoreManual) {
                try {
                    connection.setAutoCommit(false);
                } catch (SQLException e) {
                    restoreError = e;
                }
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
            if (restoreError != null) {
                throw restoreError;
            }
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

    @Override
    public boolean isExternallyManaged() {
        return true;
    }

    public DataSource dataSource() {
        return dataSource;
    }
}
