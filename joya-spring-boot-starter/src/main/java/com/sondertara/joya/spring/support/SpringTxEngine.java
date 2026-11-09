package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.tx.IllegalTxStateException;
import com.sondertara.joya.core.tx.Tx;
import com.sondertara.joya.core.tx.TxEngine;
import com.sondertara.joya.core.tx.TxException;
import com.sondertara.joya.core.tx.TxOptions;
import com.sondertara.joya.core.tx.TxOutcome;
import com.sondertara.joya.core.tx.TxSession;
import com.sondertara.joya.core.tx.TxSessionSupport;
import com.sondertara.joya.core.tx.TxState;
import com.sondertara.joya.core.tx.TxTimeoutException;
import com.sondertara.joya.core.tx.UnexpectedRollbackException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Spring 环境事务引擎：将 {@code joya.tx(...)} 显式事务委托给 {@link PlatformTransactionManager}，
 * 通过 {@link TxSession} 适配；{@link Tx} 门面由 {@code TxStarter} 统一包装为 {@code TxImpl}。
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
        if (!isCompatibleTransactionManager(transactionManager, dataSource)) {
            throw new IllegalArgumentException(
                    "SpringTxEngine requires a DataSourceTransactionManager whose resourceFactory matches the "
                            + "configured DataSource, or a JpaTransactionManager with setDataSource(...) bound to it; got: "
                            + transactionManager.getClass().getName());
        }
    }

    /**
     * 判断事务管理器关联的 {@code resourceFactory} 与目标 {@link DataSource} 在
     * {@link TransactionSynchronizationManager} 中是否映射到同一个资源键。
     */
    public static boolean matchesDataSource(Object resourceFactory, DataSource targetDataSource) {
        return SpringConnectionProvider.matchesDataSource(resourceFactory, targetDataSource);
    }

    /**
     * 兼容两类管理器：
     * <ul>
     *   <li>DataSourceTransactionManager 体系：resourceFactory 直接匹配 DataSource；</li>
     *   <li>JpaTransactionManager：必须显式 setDataSource(...)，其 JDBC ConnectionHolder 才会绑定到 DataSourceUtils。</li>
     * </ul>
     */
    static boolean isCompatibleTransactionManager(PlatformTransactionManager manager, DataSource dataSource) {
        if (manager instanceof ResourceTransactionManager rtm
                && matchesDataSource(rtm.getResourceFactory(), dataSource)) {
            return true;
        }
        if (JpaTransactionManagerSupport.isJpa(manager)) {
            DataSource jpaDataSource = JpaTransactionManagerSupport.dataSource(manager);
            return jpaDataSource != null && matchesDataSource(jpaDataSource, dataSource);
        }
        return false;
    }

    @Override
    public TxSession open(ExecutionKernel rootKernel, TxOptions options) {
        Objects.requireNonNull(rootKernel, "rootKernel must not be null");
        Objects.requireNonNull(options, "options must not be null");

        boolean existingTxForDataSource = TransactionSynchronizationManager.getResource(dataSource) != null
                && TransactionSynchronizationManager.isActualTransactionActive();
        if (existingTxForDataSource && options.hasExplicitIsolation()) {
            Integer currentIso = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (currentIso == null || currentIso != options.isolation().getValue()) {
                throw new TxException("Cannot override isolation level to " + options.isolation()
                        + " when joining an existing Spring transaction with isolation "
                        + (currentIso == null ? "DEFAULT" : currentIso));
            }
        }
        if (existingTxForDataSource && options.readOnly() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new TxException("Cannot enforce readOnly=true when joining an existing read-write Spring transaction.");
        }

        DefaultTransactionDefinition def = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED);
        if (options.hasExplicitIsolation()) {
            def.setIsolationLevel(options.isolation().getValue());
        }
        if (options.readOnly()) {
            def.setReadOnly(true);
        }
        if (options.timeout() != null) {
            def.setTimeout(TxSessionSupport.ceilSeconds(options.timeout()));
        }

        TransactionStatus status = null;
        try {
            status = transactionManager.getTransaction(def);
            return new SpringTxSession(rootKernel, transactionManager, status, options);
        } catch (Throwable beginFailure) {
            if (status != null) {
                try {
                    if (!status.isCompleted()) {
                        transactionManager.rollback(status);
                    }
                } catch (Throwable cleanupFailure) {
                    beginFailure.addSuppressed(cleanupFailure);
                }
            }
            if (beginFailure instanceof TransactionException e) {
                throw new TxException("Failed to begin Spring-managed transaction.", e);
            }
            if (beginFailure instanceof RuntimeException e) {
                throw e;
            }
            if (beginFailure instanceof Error e) {
                throw e;
            }
            throw new TxException("Failed to begin Spring-managed transaction.", beginFailure);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public void validateConfiguration(DataSource clientDataSource, ConnectionProvider connectionProvider) {
        if (clientDataSource != this.dataSource) {
            throw new IllegalArgumentException(
                    "SpringTxEngine DataSource must match JoyaClient DataSource");
        }
        if (!isCompatibleTransactionManager(this.transactionManager, clientDataSource)) {
            throw new IllegalArgumentException(
                    "SpringTxEngine requires a DataSourceTransactionManager or a JpaTransactionManager "
                            + "whose JDBC DataSource matches JoyaClient DataSource");
        }
        ConnectionProvider unwrapped = connectionProvider == null ? null : connectionProvider.unwrap();
        if (!(unwrapped instanceof SpringConnectionProvider scp)) {
            throw new IllegalArgumentException(
                    "SpringTxEngine requires a matching SpringConnectionProvider on JoyaClient.Builder");
        }
        if (scp.dataSource() != this.dataSource) {
            throw new IllegalArgumentException(
                    "SpringTxEngine DataSource must match SpringConnectionProvider DataSource");
        }
    }

    private static final class SpringTxSession implements TxSession {

        private final ExecutionKernel rootKernel;
        private final PlatformTransactionManager txManager;
        private final TransactionStatus status;
        private final boolean synchronizationActiveAtStart;
        private final TxSessionSupport<Object> support = new TxSessionSupport<>();
        private TxOutcome terminalOutcome;
        private boolean physicalRollbackConfirmed;
        private boolean rollbackOnly;
        private boolean completed;
        private boolean closed;

        SpringTxSession(ExecutionKernel rootKernel, PlatformTransactionManager txManager,
                        TransactionStatus status, TxOptions options) {
            this.rootKernel = rootKernel;
            this.txManager = txManager;
            this.status = status;
            this.synchronizationActiveAtStart = TransactionSynchronizationManager.isActualTransactionActive();
            this.support.startDeadline(options.timeout());
            if (!status.isNewTransaction() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int syncStatus) {
                        completed = true;
                        if (terminalOutcome == null) {
                            terminalOutcome = switch (syncStatus) {
                                case STATUS_COMMITTED -> TxOutcome.COMMITTED;
                                case STATUS_ROLLED_BACK -> TxOutcome.ROLLED_BACK;
                                default -> TxOutcome.UNKNOWN;
                            };
                        }
                    }
                });
            }
        }

        @Override
        public Connection acquire() throws SQLException {
            support.checkOwner();
            if (closed || !isActive()) {
                TxState current = terminalOutcome == TxOutcome.ROLLED_BACK ? TxState.ROLLED_BACK : TxState.COMMITTED;
                throw new IllegalTxStateException(current, TxState.ACTIVE, current.allowedTransitions());
            }
            support.checkDeadline(this::markRollbackOnly);
            return rootKernel.connections().acquire();
        }

        @Override
        public void release(Connection connection) throws SQLException {
            rootKernel.connections().release(connection);
        }

        @Override
        public OptionalInt queryTimeoutSeconds() {
            return support.resolveQueryTimeoutSeconds(TxSessionSupport.resolveDefaultQueryTimeout(rootKernel));
        }

        @Override
        public void enterSavepoint() {
            support.checkOwner();
            try {
                support.pushSavepoint(status.createSavepoint());
            } catch (TransactionException e) {
                throw new TxException("Failed to create savepoint", e);
            }
        }

        @Override
        public void exitSavepointCommit() {
            support.checkOwner();
            Object savepoint = support.popSavepoint();
            try {
                status.releaseSavepoint(savepoint);
            } catch (TransactionException e) {
                markRollbackOnly();
                throw new TxException("Failed to release savepoint", e);
            } catch (RuntimeException | Error e) {
                markRollbackOnly();
                throw e;
            }
        }

        @Override
        public void exitSavepointRollback() {
            support.checkOwner();
            Object savepoint = support.popSavepoint();
            try {
                status.rollbackToSavepoint(savepoint);
            } catch (Throwable rollbackFailure) {
                markRollbackOnly();
                if (rollbackFailure instanceof TransactionException te) {
                    throw new TxException("Failed to rollback to savepoint; outer transaction marked rollback-only", te);
                }
                throw rollbackFailure;
            } finally {
                try {
                    status.releaseSavepoint(savepoint);
                } catch (Throwable ignored) {
                    // 部分数据库在 rollbackToSavepoint 后自动释放保存点
                }
            }
        }

        @Override
        public void validateAfterCommitRegistration() {
            if (!status.isNewTransaction() && !TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new TxException("Cannot register afterCommit callback on joined transaction when Spring TransactionSynchronization is inactive.");
            }
        }

        @Override
        public boolean registerExternalAfterCommit(Runnable callback) {
            if (!status.isNewTransaction()) {
                validateAfterCommitRegistration();
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
            if (!status.isNewTransaction()) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    throw new TxException("Cannot register afterCompletion callback on joined transaction when Spring TransactionSynchronization is inactive.");
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int syncStatus) {
                        TxOutcome outcome;
                        if (syncStatus == TransactionSynchronization.STATUS_COMMITTED) {
                            outcome = TxOutcome.COMMITTED;
                        } else if (syncStatus == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            outcome = TxOutcome.ROLLED_BACK;
                        } else {
                            outcome = TxOutcome.UNKNOWN;
                        }
                        callback.accept(outcome);
                    }
                });
                return true;
            }
            return false;
        }

        @Override
        public void exitCommit() {
            support.checkOwner();
            if (rollbackOnly || status.isRollbackOnly()) {
                completed = true;
                if (!status.isCompleted()) {
                    txManager.rollback(status);
                    if (status.isNewTransaction()) {
                        physicalRollbackConfirmed = true;
                    }
                }
                terminalOutcome = TxOutcome.ROLLED_BACK;
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).");
            }
            if (support.isDeadlineExpired()) {
                this.rollbackOnly = true;
                completed = true;
                if (!status.isCompleted()) {
                    txManager.rollback(status);
                    if (status.isNewTransaction()) {
                        physicalRollbackConfirmed = true;
                    }
                }
                terminalOutcome = TxOutcome.ROLLED_BACK;
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
            }
            try {
                txManager.commit(status);
                completed = true;
                terminalOutcome = TxOutcome.COMMITTED;
            } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                completed = true;
                terminalOutcome = TxOutcome.ROLLED_BACK;
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).", e);
            } catch (TransactionException e) {
                completed = true;
                terminalOutcome = TxOutcome.UNKNOWN;
                throw new TxException("Commit failed.", e);
            } catch (RuntimeException | Error e) {
                completed = true;
                terminalOutcome = TxOutcome.UNKNOWN;
                throw e;
            }
        }

        @Override
        public void exitRollback() {
            support.checkOwner();
            completed = true;
            try {
                if (!status.isCompleted()) {
                    txManager.rollback(status);
                    if (status.isNewTransaction()) {
                        physicalRollbackConfirmed = true;
                    }
                }
                terminalOutcome = TxOutcome.ROLLED_BACK;
            } catch (TransactionException e) {
                terminalOutcome = TxOutcome.UNKNOWN;
                throw new TxException("Rollback failed.", e);
            } catch (RuntimeException | Error e) {
                terminalOutcome = TxOutcome.UNKNOWN;
                throw e;
            }
        }

        @Override
        public void markRollbackOnly() {
            support.checkOwner();
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
            return !completed
                    && !status.isCompleted()
                    && (!synchronizationActiveAtStart || TransactionSynchronizationManager.isActualTransactionActive());
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public TxOutcome terminalOutcome() {
            return terminalOutcome;
        }

        @Override
        public boolean isPhysicalRollbackConfirmed() {
            return physicalRollbackConfirmed && status.isNewTransaction();
        }

        @Override
        public void close() {
            support.checkOwner();
            if (closed) {
                return;
            }
            closed = true;
            if (!completed) {
                boolean activeBeforeClose = isActive();
                completed = true;
                if (!activeBeforeClose) {
                    if (terminalOutcome == null) {
                        terminalOutcome = TxOutcome.UNKNOWN;
                    }
                    return;
                }
                try {
                    if (!status.isCompleted()) {
                        txManager.rollback(status);
                        if (status.isNewTransaction()) {
                            physicalRollbackConfirmed = true;
                        }
                    }
                    terminalOutcome = TxOutcome.ROLLED_BACK;
                } catch (TransactionException e) {
                    terminalOutcome = TxOutcome.UNKNOWN;
                    throw new TxException("Failed to rollback uncommitted transaction on close.", e);
                } catch (RuntimeException | Error e) {
                    terminalOutcome = TxOutcome.UNKNOWN;
                    throw e;
                }
            }
        }
    }
}
