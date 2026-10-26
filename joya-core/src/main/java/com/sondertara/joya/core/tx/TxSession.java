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

    /** 在回调进入本地保存点队列前预检外部同步器，避免释放保存点后才发现无法注册。 */
    default void validateAfterCommitRegistration() {
        // 本地回调队列无需外部同步器。
    }

    /** 已确认的物理终态；尚未结束或结果未知时返回 {@code null}。 */
    default TxOutcome terminalOutcome() {
        return null;
    }

    /**
     * 是否已明确确认物理事务回滚成功。
     * 加入外层事务时仅标记 rollback-only 不算物理回滚，默认保守返回 {@code false}。
     */
    default boolean isPhysicalRollbackConfirmed() {
        return false;
    }

    @Override
    void close() throws SQLException;
}
