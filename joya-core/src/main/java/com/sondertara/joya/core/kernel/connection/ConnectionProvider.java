package com.sondertara.joya.core.kernel.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 连接来源端口（策略）。
 *
 * <p>执行器（{@code JdbcExecutor}）只通过本端口借还连接，<b>不读取任何线程级状态</b>。
 * 连接从哪里来、用完如何处理，完全由实现决定：
 * <ul>
 *   <li>{@link DataSourceConnectionProvider}：自动提交模式，每次借出、用完归还。</li>
 *   <li>{@code TxConnectionProvider}：绑定某个事务连接，归还为空操作。</li>
 *   <li>Spring 等外部事务桥接：由适配模块实现，核心不感知。</li>
 * </ul>
 */
public interface ConnectionProvider {

    /** 借出一条连接。 */
    Connection acquire() throws SQLException;

    /** 归还由 {@link #acquire()} 借出的连接。 */
    void release(Connection connection) throws SQLException;

    /** 语句级超时（秒）；未设置时为空。事务超时通过此处下沉到每条语句。 */
    default OptionalInt queryTimeoutSeconds() {
        return OptionalInt.empty();
    }

    /**
     * 该连接提供者是否由外部事务基础设施（如 Spring {@code DataSourceUtils}）受管。
     *
     * <p>若返回 {@code true}，则客户端必须同时显式配置匹配的外部 {@code TxEngine}（不得使用纯 JDBC 事务引擎），
     * 否则纯 JDBC 事务引擎直接调用 {@code commit()}/{@code rollback()} 会破坏外层受管事务。
     */
    default boolean isExternallyManaged() {
        return false;
    }

    /**
     * 若本提供者为包装器，解包底层原始 {@link ConnectionProvider}。
     */
    default ConnectionProvider unwrap() {
        return this;
    }

    /**
     * 为未声明自身语句超时的 {@link ConnectionProvider} 补充默认回退超时（{@code fallbackSeconds <= 0} 时原样返回）。
     *
     * <p>每次调用动态优先读取委托提供者的 {@link #queryTimeoutSeconds()}，仅在其返回空值时回退到 {@code fallbackSeconds}。
     */
    static ConnectionProvider withFallbackQueryTimeout(ConnectionProvider delegate, int fallbackSeconds) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        if (fallbackSeconds <= 0 || delegate instanceof FallbackTimeoutConnectionProvider) {
            return delegate;
        }
        return new FallbackTimeoutConnectionProvider(delegate, fallbackSeconds);
    }
}

record FallbackTimeoutConnectionProvider(ConnectionProvider delegate, int fallbackSeconds)
        implements ConnectionProvider {

    @Override
    public Connection acquire() throws SQLException {
        return delegate.acquire();
    }

    @Override
    public void release(Connection connection) throws SQLException {
        delegate.release(connection);
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        OptionalInt primary = delegate.queryTimeoutSeconds();
        return (primary != null && primary.isPresent()) ? primary : OptionalInt.of(fallbackSeconds);
    }

    @Override
    public boolean isExternallyManaged() {
        return delegate.isExternallyManaged();
    }

    @Override
    public ConnectionProvider unwrap() {
        return delegate.unwrap();
    }
}
