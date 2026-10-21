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

    public DataSourceConnectionProvider(DataSource dataSource) {
        this(dataSource, 0);
    }

    public DataSourceConnectionProvider(DataSource dataSource, int queryTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);
    }

    @Override
    public Connection acquire() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void release(Connection connection) throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        return queryTimeoutSeconds > 0 ? OptionalInt.of(queryTimeoutSeconds) : OptionalInt.empty();
    }
}
