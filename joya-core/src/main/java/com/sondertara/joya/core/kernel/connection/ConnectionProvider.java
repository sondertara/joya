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

    /**
     * 归还或销毁处于异常/未决状态的连接（如事务 rollback/commit/restore 失败）。
     *
     * <p>实现者在清理内部借出状态时，<b>严禁</b>将该连接切换为 {@code setAutoCommit(true)}，
     * 以免触发 JDBC 规范的隐式提交导致未决事务脏数据落盘。默认实现直接调用 null-safe 的
     * {@code connection.close()}，不经过 {@link #release(Connection)} 的正常归还路径；
     * 该默认实现仅适用于 {@code close()} 即销毁/归还物理连接的简单提供者，凡具备自定义归还或事务托管语义的提供者必须覆写。
     */
    default void discard(Connection connection) throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

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
     * 该连接提供者的连接是否允许调用方直接控制事务（{@code commit / rollback / setAutoCommit / setSavepoint ...}）。
     *
     * <p>事务视图与外部受管连接（Spring {@code DataSourceUtils} / JPA）必须返回 {@code false}：
     * 底层连接可能在任意时刻被外层事务绑定，直接提交会破坏外层事务边界。
     */
    default boolean allowsTransactionControl() {
        return true;
    }

    /**
     * 该提供者固定绑定的 {@link javax.sql.DataSource} 标识；自定义/动态提供者返回 {@code null}。
     * 用于构造期校验客户端 {@code DataSource} 与连接来源同源。
     */
    default javax.sql.DataSource originDataSource() {
        return null;
    }

    /**
     * 判断本提供者绑定的数据源是否与客户端目标 {@link javax.sql.DataSource} 同源
     * （未绑定固定数据源的自定义提供者返回 {@code true}；外部容器适配器可覆写以统一代理剥离语义）。
     */
    default boolean matchesDataSource(javax.sql.DataSource targetDataSource) {
        javax.sql.DataSource origin = originDataSource();
        return origin == null || (targetDataSource != null && (origin == targetDataSource || origin.equals(targetDataSource)));
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
    public void discard(Connection connection) throws SQLException {
        delegate.discard(connection);
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
    public boolean allowsTransactionControl() {
        return delegate.allowsTransactionControl();
    }

    @Override
    public javax.sql.DataSource originDataSource() {
        return delegate.originDataSource();
    }

    @Override
    public boolean matchesDataSource(javax.sql.DataSource targetDataSource) {
        return delegate.matchesDataSource(targetDataSource);
    }

    @Override
    public ConnectionProvider unwrap() {
        return delegate.unwrap();
    }
}
