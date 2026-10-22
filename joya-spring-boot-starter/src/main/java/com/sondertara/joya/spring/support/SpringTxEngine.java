package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.tx.IllegalTxStateException;
import com.sondertara.joya.core.tx.Tx;
import com.sondertara.joya.core.tx.TxEngine;
import com.sondertara.joya.core.tx.TxException;
import com.sondertara.joya.core.tx.TxImpl;
import com.sondertara.joya.core.tx.TxOptions;
import com.sondertara.joya.core.tx.TxOutcome;
import com.sondertara.joya.core.tx.TxSession;
import com.sondertara.joya.core.tx.TxState;
import com.sondertara.joya.core.tx.TxTimeoutException;
import com.sondertara.joya.core.tx.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Spring 环境事务引擎：将 {@code joya.tx(...)} 显式事务委托给 {@link PlatformTransactionManager}，
 * 通过 {@link TxSession} 适配并统一复用 {@link TxImpl}。
 *
 * <ul>
 *   <li>当外层已有针对同一 {@link DataSource} 的 {@code @Transactional} 时，以 {@code PROPAGATION_REQUIRED}
 *       自动加入外层事务（共享同一条连接），内层可通过 {@link Tx#savepoint} 使用 JDBC Savepoint 实现局部回滚。</li>
 *   <li>当外层无事务时，通过 {@link PlatformTransactionManager} 开启新事务并绑定到 {@link TransactionSynchronizationManager}，
 *       使事务块内调用的 {@code JdbcTemplate} 或其他 ORM 同样能感知该事务。</li>
 * </ul>
 */
public final class SpringTxEngine implements TxEngine {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;

    public SpringTxEngine(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    @Override
    public Tx begin(JoyaTemplate rootTemplate, TxOptions options) {
        Objects.requireNonNull(rootTemplate, "rootTemplate must not be null");
        Objects.requireNonNull(options, "options must not be null");

        boolean existingTxForDataSource = TransactionSynchronizationManager.getResource(dataSource) != null
                && TransactionSynchronizationManager.isActualTransactionActive();
        if (existingTxForDataSource && options.hasExplicitIsolation()) {
            Integer currentIso = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (currentIso != null && currentIso != options.isolation().getValue()) {
                throw new TxException("Cannot override isolation level to " + options.isolation()
                        + " when joining an existing Spring transaction with isolation " + currentIso);
            }
        }

        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
        if (options.hasExplicitIsolation()) {
            def.setIsolationLevel(options.isolation().getValue());
        }
        if (options.readOnly()) {
            def.setReadOnly(true);
        }
        if (options.timeout() != null) {
            int timeoutSec = (int) Math.max(1, (options.timeout().toNanos() + 999_999_999L) / 1_000_000_000L);
            def.setTimeout(timeoutSec);
        }

        try {
            TransactionStatus status = transactionManager.getTransaction(def);
            return new TxImpl(rootTemplate, new SpringTxSession(rootTemplate, transactionManager, status, options));
        } catch (TransactionException e) {
            throw new TxException("Failed to begin Spring-managed transaction.", e);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    private static final class SpringTxSession implements TxSession {

        private final JoyaTemplate rootTemplate;
        private final PlatformTransactionManager txManager;
        private final TransactionStatus status;
        private final Thread ownerThread;
        private final long deadlineNanos;
        private final Deque<Object> savepoints = new ArrayDeque<>();
        private boolean rollbackOnly;
        private boolean completed;
        private boolean closed;

        SpringTxSession(JoyaTemplate rootTemplate, PlatformTransactionManager txManager,
                        TransactionStatus status, TxOptions options) {
            this.rootTemplate = rootTemplate;
            this.txManager = txManager;
            this.status = status;
            this.ownerThread = Thread.currentThread();
            this.deadlineNanos = options.timeout() == null ? 0L : System.nanoTime() + options.timeout().toNanos();
        }

        @Override
        public Connection acquire() throws SQLException {
            checkOwner();
            if (closed || completed) {
                throw new IllegalTxStateException(TxState.COMMITTED, TxState.ACTIVE, TxState.COMMITTED.allowedTransitions());
            }
            checkDeadline();
            return rootTemplate.connections().acquire();
        }

        @Override
        public void release(Connection connection) throws SQLException {
            rootTemplate.connections().release(connection);
        }

        @Override
        public OptionalInt queryTimeoutSeconds() {
            if (deadlineNanos != 0L) {
                long remaining = deadlineNanos - System.nanoTime();
                return OptionalInt.of((int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
            }
            return rootTemplate.connections().queryTimeoutSeconds();
        }

        @Override
        public void enterSavepoint() {
            checkOwner();
            try {
                savepoints.push(status.createSavepoint());
            } catch (TransactionException e) {
                throw new TxException("Failed to create savepoint", e);
            }
        }

        @Override
        public void exitSavepointCommit() {
            checkOwner();
            Object savepoint = savepoints.pop();
            try {
                status.releaseSavepoint(savepoint);
            } catch (TransactionException e) {
                throw new TxException("Failed to release savepoint", e);
            }
        }

        @Override
        public void exitSavepointRollback() {
            checkOwner();
            Object savepoint = savepoints.pop();
            try {
                status.rollbackToSavepoint(savepoint);
            } finally {
                try {
                    status.releaseSavepoint(savepoint);
                } catch (Throwable ignored) {
                    // 部分数据库在 rollbackToSavepoint 后自动释放保存点
                }
            }
        }

        @Override
        public boolean registerExternalAfterCommit(Runnable callback) {
            if (!status.isNewTransaction() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        callback.run();
                    }
                });
                return true;
            }
            return false;
        }

        @Override
        public boolean registerExternalAfterCompletion(Consumer<TxOutcome> callback) {
            if (!status.isNewTransaction() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int syncStatus) {
                        TxOutcome outcome = (syncStatus == TransactionSynchronization.STATUS_COMMITTED)
                                ? TxOutcome.COMMITTED
                                : TxOutcome.ROLLED_BACK;
                        callback.accept(outcome);
                    }
                });
                return true;
            }
            return false;
        }

        @Override
        public void exitCommit() {
            checkOwner();
            if (rollbackOnly || status.isRollbackOnly()) {
                completed = true;
                if (!status.isCompleted()) {
                    txManager.rollback(status);
                }
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).");
            }
            try {
                txManager.commit(status);
                completed = true;
            } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                completed = true;
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).", e);
            } catch (TransactionException e) {
                completed = true;
                throw new TxException("Commit failed.", e);
            } catch (RuntimeException e) {
                completed = true;
                throw e;
            }
        }

        @Override
        public void exitRollback() {
            checkOwner();
            completed = true;
            try {
                if (!status.isCompleted()) {
                    txManager.rollback(status);
                }
            } catch (TransactionException e) {
                throw new TxException("Rollback failed.", e);
            }
        }

        @Override
        public void markRollbackOnly() {
            checkOwner();
            this.rollbackOnly = true;
            if (!status.isCompleted()) {
                status.setRollbackOnly();
            }
        }

        @Override
        public boolean isRollbackOnly() {
            return rollbackOnly || status.isRollbackOnly();
        }

        @Override
        public boolean isActive() {
            return !completed && !status.isCompleted();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!completed) {
                completed = true;
                try {
                    if (!status.isCompleted()) {
                        txManager.rollback(status);
                    }
                } catch (TransactionException e) {
                    throw new TxException("Failed to rollback uncommitted transaction on close.", e);
                }
            }
        }

        private void checkOwner() {
            if (Thread.currentThread() != ownerThread) {
                throw new TxException("Transaction is owned by thread [" + ownerThread.getName()
                        + "] but was used by [" + Thread.currentThread().getName()
                        + "]. A transaction must not cross threads (invariant I9).");
            }
        }

        private void checkDeadline() {
            if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                markRollbackOnly();
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
            }
        }
    }
}
