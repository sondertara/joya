package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;

import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * 底层事务会话 SPI：屏蔽纯 JDBC 连接事务（{@link TxConnection}）与外部容器事务（如 Spring {@code TransactionStatus}）
 * 的物理生命周期差异，使 {@link TxImpl} 成为唯一的 {@link Tx} 门面实现。
 */
public interface TxSession extends ConnectionProvider, AutoCloseable {

    void enterSavepoint() throws SQLException;

    void exitSavepointCommit() throws SQLException;

    void exitSavepointRollback() throws SQLException;

    void exitCommit() throws SQLException;

    void exitRollback() throws SQLException;

    void markRollbackOnly();

    boolean isRollbackOnly();

    boolean isActive();

    boolean isClosed();

    /**
     * 若当前会话是加入外层既有容器事务（而非新建物理事务），可拦截注册到外层同步器并返回 {@code true}；
     * 默认返回 {@code false}，交由 {@link TxImpl} 本地队列在 {@code commit / rollback / close} 时触发。
     */
    default boolean registerExternalAfterCommit(Runnable callback) {
        return false;
    }

    default boolean registerExternalAfterCompletion(Consumer<TxOutcome> callback) {
        return false;
    }

    @Override
    void close() throws SQLException;
}
