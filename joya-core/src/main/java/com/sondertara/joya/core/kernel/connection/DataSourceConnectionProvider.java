package com.sondertara.joya.core.kernel.connection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 自动提交模式的连接来源：每次借出一条新连接，用完立即归还连接池。
 *
 * <p>不做任何线程绑定或引用计数，因此不存在"一个调用关掉另一个调用正在使用的连接"的问题。
 */
public final class DataSourceConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    private final java.util.Set<Connection> flippedAutoCommit =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    public DataSourceConnectionProvider(DataSource dataSource) {
        this(dataSource, 0);
    }

    public DataSourceConnectionProvider(DataSource dataSource, int queryTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);
    }

    @Override
    public Connection acquire() throws SQLException {
        Connection conn = dataSource.getConnection();
        try {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
                flippedAutoCommit.add(conn);
            }
            return conn;
        } catch (SQLException | RuntimeException e) {
            flippedAutoCommit.remove(conn);
            try {
                conn.close();
            } catch (SQLException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    @Override
    public void release(Connection connection) throws SQLException {
        if (connection != null) {
            boolean restoreManual = flippedAutoCommit.remove(connection);
            SQLException restoreError = null;
            if (restoreManual) {
                try {
                    connection.setAutoCommit(false);
                } catch (SQLException e) {
                    restoreError = e;
                }
            }
            try {
                connection.close();
            } catch (SQLException closeEx) {
                if (restoreError != null) {
                    restoreError.addSuppressed(closeEx);
                    throw restoreError;
                }
                throw closeEx;
            }
            if (restoreError != null) {
                throw restoreError;
            }
        }
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        return queryTimeoutSeconds > 0 ? OptionalInt.of(queryTimeoutSeconds) : OptionalInt.empty();
    }
}
