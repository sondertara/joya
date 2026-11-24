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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceHolder;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
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
 *
 * <p><b>多数据源与逃逸句柄交错边界说明</b>：Spring {@link TransactionSynchronizationManager} 采用线程级全局单槽位而非栈式帧模型。
 * 本引擎通过 {@code SyncOwnerMarker} 与乱序同步补偿覆盖了跨库嵌套重入及“单句柄逃逸 + 外层异源事务先完成”下的
 * {@code commit()} / {@code rollback()} / {@code close()} 安全性；但若出现“多个句柄同时逃逸且中间交错开启第三方新事务”
 * 的极端非栈序拓扑，受限于 Spring 全局单槽位表达上限，仅由 {@code hasAnyActiveResourceHolder()} 保守兜底以避免误清仍活跃的资源持有者状态。
 * 生产代码应始终在 {@code joya.tx(...)} 作用域或 {@code try-with-resources} 栈序边界内闭合 {@link Tx}。
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

        boolean requiresNew = options.requiresNew();
        Object existingResource = requiresNew ? null : TransactionSynchronizationManager.getResource(dataSource);
        boolean existingTxForDataSource = existingResource != null
                && (!(existingResource instanceof ResourceHolder rh) || !rh.isVoid())
                && (TransactionSynchronizationManager.isActualTransactionActive()
                        || (existingResource instanceof ConnectionHolder ch && ch.isSynchronizedWithTransaction()));
        if (existingTxForDataSource) {
            validateJoinedOptions(options, existingResource);
        }

        int propagation = requiresNew
                ? TransactionDefinition.PROPAGATION_REQUIRES_NEW
                : TransactionDefinition.PROPAGATION_REQUIRED;
        DefaultTransactionDefinition def = new DefaultTransactionDefinition(propagation);
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
            return new SpringTxSession(rootKernel, dataSource, transactionManager, status, options);
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

    private void validateJoinedOptions(TxOptions options, Object existingResource) {
        if (!options.hasExplicitIsolation() && !options.readOnly()) {
            return;
        }
        if (isCurrentSynchronizationOwnedBy(dataSource)) {
            if (options.hasExplicitIsolation()) {
                Integer currentIso = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                if (currentIso == null || currentIso != options.isolation().getValue()) {
                    throw new TxException("Cannot override isolation level to " + options.isolation()
                            + " when joining an existing Spring transaction with isolation "
                            + (currentIso == null ? "DEFAULT" : currentIso));
                }
            }
            if (options.readOnly() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new TxException("Cannot enforce readOnly=true when joining an existing read-write Spring transaction.");
            }
            return;
        }
        // 跨数据源交错重入场景：全局 TransactionSynchronizationManager 归属内层异源事务，直接基于本库物理连接核验
        if (existingResource instanceof ConnectionHolder holder && holder.getConnectionHandle() != null) {
            try {
                Connection conn = holder.getConnection();
                if (options.hasExplicitIsolation() && conn.getTransactionIsolation() != options.isolation().getValue()) {
                    throw new TxException("Cannot override isolation level to " + options.isolation()
                            + " when joining an existing Spring transaction.");
                }
                if (options.readOnly() && !conn.isReadOnly()) {
                    throw new TxException("Cannot enforce readOnly=true when joining an existing read-write Spring transaction.");
                }
                return;
            } catch (SQLException e) {
                throw new TxException("Failed to inspect joined Spring transaction connection state.", e);
            }
        }
        if (options.hasExplicitIsolation()) {
            throw new TxException("Cannot override isolation level to " + options.isolation()
                    + " when joining an existing interleaved Spring transaction.");
        }
        if (options.readOnly()) {
            throw new TxException("Cannot enforce readOnly=true when joining an existing interleaved Spring transaction.");
        }
    }

    /**
     * 判断当前线程栈顶活跃的 {@link TransactionSynchronizationManager} 同步槽位是否归属于指定 {@link DataSource}：
     * <ul>
     *   <li>若同步器列表中包含 Joya 注册的 {@link SyncOwnerMarker}，直接比对属主 {@code DataSource}；</li>
     *   <li>若外/中层均由原生 {@code @Transactional} 开启（无标记），检查 {@code getResourceMap()} 是否同时存在其他异源
     *       {@code DataSource} 的活跃事务连接——若存在，说明当前同步器已被内层异源 {@code suspend(null)} 接管，不可错挂。</li>
     * </ul>
     */
    static boolean isCurrentSynchronizationOwnedBy(DataSource targetDataSource) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof SyncOwnerMarker marker) {
                return matchesDataSource(marker.dataSource(), targetDataSource);
            }
        }
        for (Map.Entry<Object, Object> entry : TransactionSynchronizationManager.getResourceMap().entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (key instanceof DataSource boundDs
                    && value instanceof ConnectionHolder holder
                    && !holder.isVoid()
                    && holder.isSynchronizedWithTransaction()
                    && !matchesDataSource(boundDs, targetDataSource)) {
                return false;
            }
        }
        return true;
    }

    private record SyncOwnerMarker(DataSource dataSource) implements TransactionSynchronization {
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public void validateConfiguration(DataSource clientDataSource, ConnectionProvider connectionProvider) {
        if (!matchesDataSource(this.dataSource, clientDataSource)) {
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
        if (!matchesDataSource(this.dataSource, scp.dataSource())) {
            throw new IllegalArgumentException(
                    "SpringTxEngine DataSource must match SpringConnectionProvider DataSource");
        }
    }

    private static final class SpringTxSession implements TxSession {

        private final ExecutionKernel rootKernel;
        private final DataSource dataSource;
        private final PlatformTransactionManager txManager;
        private final TransactionStatus status;
        private final Object joinedResourceAtStart;
        private final boolean synchronizationOwnedAtStart;
        private final TxSessionSupport<Object> support = new TxSessionSupport<>();
        private TxOutcome terminalOutcome;
        private boolean physicalRollbackConfirmed;
        private boolean rollbackOnly;
        private boolean completed;
        private boolean closed;

        SpringTxSession(ExecutionKernel rootKernel, DataSource dataSource, PlatformTransactionManager txManager,
                        TransactionStatus status, TxOptions options) {
            this.rootKernel = rootKernel;
            this.dataSource = dataSource;
            this.txManager = txManager;
            this.status = status;
            this.support.startDeadline(options.timeout());
            if (status.isNewTransaction()) {
                this.joinedResourceAtStart = null;
                boolean ownsNewSync = (status instanceof DefaultTransactionStatus dts)
                        ? dts.isNewSynchronization()
                        : TransactionSynchronizationManager.isSynchronizationActive();
                this.synchronizationOwnedAtStart = ownsNewSync;
                if (ownsNewSync && TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new SyncOwnerMarker(dataSource));
                }
            } else {
                this.joinedResourceAtStart = TransactionSynchronizationManager.getResource(dataSource);
                this.synchronizationOwnedAtStart = isCurrentSynchronizationOwnedBy(dataSource);
                if (this.synchronizationOwnedAtStart) {
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
        }

        @Override
        public Connection acquire() throws SQLException {
            support.checkOwner();
            if (closed || !isActive()) {
                TxState current = terminalOutcome == TxOutcome.ROLLED_BACK ? TxState.ROLLED_BACK : TxState.COMMITTED;
                throw new IllegalTxStateException(current, TxState.ACTIVE, current.allowedTransitions());
            }
            support.checkDeadline(this::markRollbackOnly);
            deadlineTimeoutSeconds();
            return rootKernel.connections().acquire();
        }

        @Override
        public void release(Connection connection) throws SQLException {
            rootKernel.connections().release(connection);
        }

        @Override
        public OptionalInt queryTimeoutSeconds() {
            return minPositive(deadlineTimeoutSeconds(), TxSessionSupport.resolveDefaultQueryTimeout(rootKernel));
        }

        @Override
        public OptionalInt deadlineTimeoutSeconds() {
            OptionalInt ownDeadline = support.resolveQueryTimeoutSeconds(OptionalInt.empty(), this::markRollbackOnly);
            OptionalInt holderDeadline;
            try {
                holderDeadline = SpringConnectionProvider.currentTransactionDeadlineSeconds(dataSource);
            } catch (TransactionTimedOutException e) {
                markRollbackOnly();
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.", e);
            }
            return minPositive(ownDeadline, holderDeadline);
        }

        private static OptionalInt minPositive(OptionalInt a, OptionalInt b) {
            if (a == null || a.isEmpty()) {
                return b != null ? b : OptionalInt.empty();
            }
            if (b == null || b.isEmpty()) {
                return a;
            }
            return OptionalInt.of(Math.min(a.getAsInt(), b.getAsInt()));
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
            if (!status.isNewTransaction()) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    throw new TxException("Cannot register afterCommit callback on joined transaction when Spring TransactionSynchronization is inactive.");
                }
                if (!isCurrentSynchronizationOwnedBy(dataSource)) {
                    throw new TxException("Cannot register afterCommit callback on joined transaction when current thread's "
                            + "Spring TransactionSynchronization is owned by another DataSource transaction.");
                }
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
                if (!isCurrentSynchronizationOwnedBy(dataSource)) {
                    throw new TxException("Cannot register afterCompletion callback on joined transaction when current thread's "
                            + "Spring TransactionSynchronization is owned by another DataSource transaction.");
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
            if (closed || !isActive()) {
                TxState current = terminalOutcome == TxOutcome.ROLLED_BACK ? TxState.ROLLED_BACK : TxState.COMMITTED;
                throw new IllegalTxStateException(current, TxState.COMMITTED, current.allowedTransitions());
            }
            if (rollbackOnly || status.isRollbackOnly()) {
                completed = true;
                rollbackUnderlying();
                terminalOutcome = TxOutcome.ROLLED_BACK;
                throw new UnexpectedRollbackException("Transaction was marked rollback-only; commit refused (invariant I7).");
            }
            if (support.isDeadlineExpired()) {
                this.rollbackOnly = true;
                completed = true;
                rollbackUnderlying();
                terminalOutcome = TxOutcome.ROLLED_BACK;
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
            }
            try {
                commitUnderlying();
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
                rollbackUnderlying();
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
            if (completed || status.isCompleted()) {
                return false;
            }
            if (status.isNewTransaction()) {
                return true;
            }
            return isJoinedOuterTransactionActive();
        }

        private boolean isJoinedOuterTransactionActive() {
            if (joinedResourceAtStart != null) {
                Object current = TransactionSynchronizationManager.getResource(dataSource);
                if (current != joinedResourceAtStart) {
                    return false;
                }
                return !(current instanceof ResourceHolder rh && rh.isVoid());
            }
            return !synchronizationOwnedAtStart || TransactionSynchronizationManager.isActualTransactionActive();
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
                // 新建物理事务（status.isNewTransaction() == true）持有绑定的 ConnectionHolder 与数据库连接，
                // 无论外层异源事务状态如何，未完成时必须无条件通过 txManager.rollback(status) 解绑并归还连接！
                boolean shouldRollback = status.isNewTransaction() ? !status.isCompleted() : isActive();
                completed = true;
                if (!shouldRollback) {
                    if (terminalOutcome == null) {
                        terminalOutcome = TxOutcome.UNKNOWN;
                    }
                    return;
                }
                try {
                    rollbackUnderlying();
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

        private void commitUnderlying() {
            if (status.isCompleted()) {
                return;
            }
            runWithOutOfOrderSyncGuard(() -> txManager.commit(status));
        }

        private void rollbackUnderlying() {
            if (status.isCompleted()) {
                return;
            }
            runWithOutOfOrderSyncGuard(() -> {
                txManager.rollback(status);
                if (status.isNewTransaction()) {
                    physicalRollbackConfirmed = true;
                }
            });
        }

        /**
         * 当新建事务（{@code status.isNewTransaction() == true}）逃逸出外层异源事务作用域、且外层违反栈序提前完成并清空了
         * {@link TransactionSynchronizationManager} 时，临时补建空同步槽位以确保 Spring {@code processCommit} / {@code processRollback}
         * 顺利完成物理提交/回滚与 {@link ConnectionHolder} 解绑，并在 {@code finally} 中清除 {@code resume()} 恢复回来的过期同步快照。
         */
        private void runWithOutOfOrderSyncGuard(Runnable action) {
            boolean syncDeactivatedOutOfOrder = status.isNewTransaction()
                    && synchronizationOwnedAtStart
                    && !TransactionSynchronizationManager.isSynchronizationActive();
            if (syncDeactivatedOutOfOrder) {
                TransactionSynchronizationManager.initSynchronization();
            }
            try {
                action.run();
            } finally {
                if (syncDeactivatedOutOfOrder
                        && !hasAnyActiveResourceHolder()
                        && TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.clear();
                }
            }
        }

        private static boolean hasAnyActiveResourceHolder() {
            for (Object value : TransactionSynchronizationManager.getResourceMap().values()) {
                if (value instanceof ConnectionHolder holder
                        && !holder.isVoid()
                        && holder.isSynchronizedWithTransaction()) {
                    return true;
                }
            }
            return false;
        }
    }
}
