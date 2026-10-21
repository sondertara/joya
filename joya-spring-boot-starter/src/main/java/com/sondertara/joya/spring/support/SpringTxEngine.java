package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.tx.TxEngine;
import com.sondertara.joya.core.proxy.JdkMapperProxy;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.tx.IllegalTxStateException;
import com.sondertara.joya.core.tx.Tx;
import com.sondertara.joya.core.tx.TxCallback;
import com.sondertara.joya.core.tx.TxConsumer;
import com.sondertara.joya.core.tx.TxException;
import com.sondertara.joya.core.tx.TxOptions;
import com.sondertara.joya.core.tx.TxOutcome;
import com.sondertara.joya.core.tx.TxState;
import com.sondertara.joya.core.tx.TxTimeoutException;
import com.sondertara.joya.core.tx.UnexpectedRollbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Spring 环境事务引擎：将 {@code joya.tx(...)} 显式事务委托给 {@link PlatformTransactionManager}。
 *
 * <ul>
 *   <li>当外层已有针对同一 {@link DataSource} 的 {@code @Transactional} 时，以 {@code PROPAGATION_REQUIRED}
 *       自动加入外层事务（共享同一条连接），内层可通过 {@link Tx#savepoint} 使用 JDBC Savepoint 实现局部回滚。</li>
 *   <li>当外层无事务时，通过 {@link PlatformTransactionManager} 开启新事务并绑定到 {@link TransactionSynchronizationManager}，
 *       使事务块内调用的 {@code JdbcTemplate} 或其他 ORM 同样能感知该事务。</li>
 * </ul>
 */
public final class SpringTxEngine implements TxEngine {

    private static final Logger log = LoggerFactory.getLogger(SpringTxEngine.class);

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
            return new SpringTx(rootTemplate, transactionManager, status, options);
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

    private static final class SpringTx implements Tx, ConnectionProvider {

        private final JoyaTemplate rootTemplate;
        private final JoyaTemplate boundTemplate;
        private final PlatformTransactionManager txManager;
        private final TransactionStatus status;
        private final Thread ownerThread;
        private final long deadlineNanos;
        private final List<Runnable> afterCommit = new ArrayList<>();
        private final List<Consumer<TxOutcome>> afterCompletion = new ArrayList<>();
        private boolean rollbackOnly;
        private boolean completed;
        private boolean closed;

        SpringTx(JoyaTemplate rootTemplate, PlatformTransactionManager txManager, TransactionStatus status, TxOptions options) {
            this.rootTemplate = rootTemplate;
            this.txManager = txManager;
            this.status = status;
            this.ownerThread = Thread.currentThread();
            this.deadlineNanos = options.timeout() == null ? 0L : System.nanoTime() + options.timeout().toNanos();
            this.boundTemplate = rootTemplate.bind(this);
        }

        // ===== DataOps =====

        @Override
        public <T> Query<T> from(Class<T> entityType) {
            return new Query<>(boundTemplate, entityType);
        }

        @Override
        public <T> EntityOps<T> of(Class<T> entityType) {
            return new EntityOps<>(boundTemplate, entityType);
        }

        @Override
        public SqlOps sql(String sql, Object... args) {
            return new SqlOps(boundTemplate, sql, args);
        }

        @Override
        public SqlOps sql(SqlStatement statement) {
            return new SqlOps(boundTemplate, statement);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public <M extends JoyaMapper> M mapper(Class<M> mapperType) {
            Objects.requireNonNull(mapperType, "mapperType must not be null");
            if (!mapperType.isInterface()) {
                throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
            }
            return new JdkMapperProxy(this).create(mapperType);
        }

        // ===== ConnectionProvider (delegates to SpringConnectionProvider with owner & deadline guard) =====

        @Override
        public Connection acquire() throws java.sql.SQLException {
            checkOwner();
            if (closed || completed) {
                throw new IllegalTxStateException(TxState.COMMITTED, TxState.ACTIVE, TxState.COMMITTED.allowedTransitions());
            }
            checkDeadline();
            return rootTemplate.connections().acquire();
        }

        @Override
        public void release(Connection connection) throws java.sql.SQLException {
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

        // ===== Savepoint =====

        @Override
        public void savepoint(TxConsumer body) {
            Objects.requireNonNull(body, "savepoint body must not be null");
            savepointCall(tx -> {
                body.accept(tx);
                return null;
            });
        }

        @Override
        public <R> R savepointCall(TxCallback<R> body) {
            Objects.requireNonNull(body, "savepointCall body must not be null");
            checkOwner();
            Object savepoint;
            try {
                savepoint = status.createSavepoint();
            } catch (TransactionException e) {
                throw new TxException("Failed to create savepoint", e);
            }
            R result;
            try {
                result = body.apply(this);
            } catch (Throwable t) {
                try {
                    status.rollbackToSavepoint(savepoint);
                } catch (Throwable rollbackEx) {
                    t.addSuppressed(rollbackEx);
                }
                try {
                    status.releaseSavepoint(savepoint);
                } catch (Throwable ignored) {
                    // 部分数据库在 rollbackToSavepoint 后自动释放保存点
                }
                throw rethrow(t);
            }
            try {
                status.releaseSavepoint(savepoint);
            } catch (TransactionException e) {
                throw new TxException("Failed to release savepoint", e);
            }
            return result;
        }

        // ===== Callbacks =====

        @Override
        public void afterCommit(Runnable callback) {
            Objects.requireNonNull(callback, "callback must not be null");
            if (!status.isNewTransaction() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runQuietly(callback, "afterCommit");
                    }
                });
            } else {
                afterCommit.add(callback);
            }
        }

        @Override
        public void afterCompletion(Consumer<TxOutcome> callback) {
            Objects.requireNonNull(callback, "callback must not be null");
            if (!status.isNewTransaction() && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int syncStatus) {
                        TxOutcome outcome = (syncStatus == TransactionSynchronization.STATUS_COMMITTED)
                                ? TxOutcome.COMMITTED
                                : TxOutcome.ROLLED_BACK;
                        runQuietly(() -> callback.accept(outcome), "afterCompletion");
                    }
                });
            } else {
                afterCompletion.add(callback);
            }
        }

        // ===== Lifecycle =====

        @Override
        public void commit() {
            checkOwner();
            if (completed) {
                throw new IllegalTxStateException(TxState.COMMITTED, TxState.COMMITTED, TxState.COMMITTED.allowedTransitions(),
                        new IllegalStateException("transaction already completed"));
            }
            if (rollbackOnly || status.isRollbackOnly()) {
                try {
                    if (!status.isCompleted()) {
                        txManager.rollback(status);
                    }
                } finally {
                    complete(TxOutcome.ROLLED_BACK);
                }
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).");
            }
            try {
                txManager.commit(status);
            } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                complete(TxOutcome.ROLLED_BACK);
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).", e);
            } catch (TransactionException e) {
                complete(TxOutcome.ROLLED_BACK);
                throw new TxException("Commit failed.", e);
            } catch (RuntimeException e) {
                complete(TxOutcome.ROLLED_BACK);
                throw e;
            }
            complete(TxOutcome.COMMITTED);
        }

        @Override
        public void rollback() {
            checkOwner();
            try {
                if (!completed && !status.isCompleted()) {
                    txManager.rollback(status);
                }
            } catch (TransactionException e) {
                complete(TxOutcome.ROLLED_BACK);
                throw new TxException("Rollback failed.", e);
            }
            complete(TxOutcome.ROLLED_BACK);
        }

        @Override
        public void setRollbackOnly() {
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
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!completed) {
                try {
                    if (!status.isCompleted()) {
                        txManager.rollback(status);
                    }
                } catch (TransactionException e) {
                    complete(TxOutcome.ROLLED_BACK);
                    throw new TxException("Failed to rollback uncommitted transaction on close.", e);
                }
                complete(TxOutcome.ROLLED_BACK);
            }
        }

        private void complete(TxOutcome outcome) {
            if (completed) {
                return;
            }
            completed = true;
            if (outcome == TxOutcome.COMMITTED) {
                for (Runnable callback : afterCommit) {
                    runQuietly(callback, "afterCommit");
                }
            }
            for (Consumer<TxOutcome> callback : afterCompletion) {
                runQuietly(() -> callback.accept(outcome), "afterCompletion");
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
                setRollbackOnly();
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
            }
        }

        private static void runQuietly(Runnable callback, String phase) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                log.warn("Transaction {} callback failed", phase, e);
            }
        }

        private static RuntimeException rethrow(Throwable t) {
            if (t instanceof RuntimeException re) {
                return re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            return new TxException("Transaction body failed.", t);
        }
    }
}
