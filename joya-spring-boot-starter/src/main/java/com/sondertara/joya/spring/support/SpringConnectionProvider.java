package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.connection.AutoCommitFlipTracker;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;

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
    private final AutoCommitFlipTracker autoCommitFlips = new AutoCommitFlipTracker();

    public SpringConnectionProvider(DataSource dataSource, int defaultTimeoutSeconds) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.defaultTimeoutSeconds = Math.max(0, defaultTimeoutSeconds);
    }

    @Override
    public Connection acquire() throws SQLException {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            if (!DataSourceUtils.isConnectionTransactional(conn, dataSource)) {
                autoCommitFlips.flipToAutoCommit(conn);
            }
            return conn;
        } catch (SQLException | RuntimeException e) {
            autoCommitFlips.forget(conn);
            DataSourceUtils.releaseConnection(conn, dataSource);
            throw e;
        }
    }

    @Override
    public void release(Connection connection) throws SQLException {
        if (connection != null) {
            SQLException restoreError = null;
            try {
                autoCommitFlips.restore(connection);
            } catch (SQLException e) {
                restoreError = e;
            }
            DataSourceUtils.releaseConnection(connection, dataSource);
            if (restoreError != null) {
                throw restoreError;
            }
        }
    }

    @Override
    public void discard(Connection connection) {
        if (connection != null) {
            autoCommitFlips.forget(connection);
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

    @Override
    public boolean isExternallyManaged() {
        return true;
    }

    /**
     * 连接可能被外层 Spring 事务绑定（{@code DataSourceUtils.getConnection} 会返回线程绑定连接），
     * 因此禁止调用方直接 commit / rollback / setAutoCommit，避免破坏外层事务边界。
     */
    @Override
    public boolean allowsTransactionControl() {
        return false;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public DataSource originDataSource() {
        return dataSource;
    }

    @Override
    public boolean matchesDataSource(DataSource targetDataSource) {
        return matchesDataSource(this.dataSource, targetDataSource);
    }

    /**
     * 判断事务管理器或连接提供者关联的 {@code resourceFactory} 与目标 {@link DataSource} 在
     * {@link TransactionSynchronizationManager} 中是否映射到同一个资源键（仅剥离 {@link InfrastructureProxy}，
     * 不剥离 {@code DelegatingDataSource}，避免包装数据源与底层目标数据源因资源键不同而静默不共享连接）。
     */
    public static boolean matchesDataSource(Object resourceFactory, DataSource targetDataSource) {
        if (resourceFactory == null || targetDataSource == null) {
            return false;
        }
        if (resourceFactory == targetDataSource || resourceFactory.equals(targetDataSource)) {
            return true;
        }
        Object unwrappedFactory = unwrapResourceFactory(resourceFactory);
        Object unwrappedTarget = unwrapResourceFactory(targetDataSource);
        return unwrappedFactory == unwrappedTarget || unwrappedFactory.equals(unwrappedTarget);
    }

    private static Object unwrapResourceFactory(Object candidate) {
        Object current = candidate;
        while (current instanceof InfrastructureProxy proxy) {
            Object unwrapped = proxy.getWrappedObject();
            if (unwrapped != null && unwrapped != current) {
                current = unwrapped;
                continue;
            }
            break;
        }
        return current;
    }
}
