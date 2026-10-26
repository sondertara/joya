package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.executor.ResultSetSpliterator;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.proxy.JdkMapperProxy;
import com.sondertara.joya.core.query.dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * {@link Tx} 的统一实现：绑定一个底层 {@link TxSession}（纯 JDBC {@link TxConnection} 或 Spring 事务会话）
 * 与一份事务态 {@link JoyaTemplate}。
 */
public final class TxImpl implements Tx {

    private static final Logger log = LoggerFactory.getLogger(TxImpl.class);

    private final TxSession session;
    private final JoyaTemplate template;
    private final Thread ownerThread;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Consumer<TxOutcome>> afterCompletion = new ArrayList<>();
    private final List<Runnable> streamCloseActions = new ArrayList<>();
    private final java.util.Deque<List<Runnable>> savepointAfterCommitStack = new java.util.ArrayDeque<>();
    private java.util.Map<Class<?>, Object> mapperCache;
    private TxOutcome outcome;
    private int openTransactionalStreams;

    public TxImpl(JoyaTemplate rootTemplate, TxSession session) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.ownerThread = Thread.currentThread();
        this.template = Objects.requireNonNull(rootTemplate, "rootTemplate must not be null")
                .bind(new TransactionalConnectionProvider(session));
    }

    public boolean isCompleted() {
        return outcome != null;
    }

    TxOutcome outcome() {
        return outcome;
    }

    boolean isPhysicalRollbackConfirmed() {
        return outcome == TxOutcome.ROLLED_BACK && session.isPhysicalRollbackConfirmed();
    }

    boolean hasTransactionalStreams() {
        return openTransactionalStreams > 0;
    }

    // ===== DataOps：事务视图 =====

    @Override
    public <T> Query<T> from(Class<T> entityType) {
        return new Query<>(template, entityType);
    }

    @Override
    public <T> EntityOps<T> of(Class<T> entityType) {
        return new EntityOps<>(template, entityType);
    }

    @Override
    public SqlOps sql(String sql, Object... args) {
        return new SqlOps(template, sql, args);
    }

    @Override
    public SqlOps sql(SqlStatement statement) {
        return new SqlOps(template, statement);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M> M mapper(Class<M> mapperType) {
        Objects.requireNonNull(mapperType, "mapperType must not be null");
        if (!mapperType.isInterface()) {
            throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
        }
        if (mapperCache == null) {
            mapperCache = new java.util.HashMap<>(4);
        }
        return (M) mapperCache.computeIfAbsent(mapperType, cls -> new JdkMapperProxy(this).create((Class) cls));
    }

    @Override
    public JoyaSettings settings() {
        return JoyaSettings.of(template.executionOptions(), template.namingPolicy());
    }

    @Override
    public com.sondertara.joya.core.kernel.dialect.DbType dbType() {
        return template.dialect().dbType();
    }

    // ===== 保存点（Savepoint） =====

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
        checkOwner();
        Objects.requireNonNull(body, "savepointCall body must not be null");
        try {
            session.enterSavepoint();
        } catch (SQLException e) {
            throw new TxException("Failed to create savepoint", e);
        }
        savepointAfterCommitStack.push(new ArrayList<>());
        R result;
        try {
            result = body.apply(this);
            if (!savepointAfterCommitStack.peek().isEmpty()) {
                session.validateAfterCommitRegistration();
            }
        } catch (Throwable t) {
            savepointAfterCommitStack.pop();
            try {
                session.exitSavepointRollback();
            } catch (Throwable rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
            throw rethrow(t);
        }
        try {
            session.exitSavepointCommit();
        } catch (SQLException e) {
            savepointAfterCommitStack.pop();
            session.markRollbackOnly();
            throw new TxException("Failed to release savepoint", e);
        } catch (RuntimeException | Error e) {
            savepointAfterCommitStack.pop();
            session.markRollbackOnly();
            throw e;
        }
        List<Runnable> spCallbacks = savepointAfterCommitStack.pop();
        for (Runnable cb : spCallbacks) {
            afterCommit(cb);
        }
        return result;
    }

    // ===== 回调 =====

    @Override
    public void afterCommit(Runnable callback) {
        checkOwner();
        Objects.requireNonNull(callback, "callback must not be null");
        session.validateAfterCommitRegistration();
        if (!savepointAfterCommitStack.isEmpty()) {
            savepointAfterCommitStack.peek().add(callback);
            return;
        }
        if (!session.registerExternalAfterCommit(() -> runQuietly(callback, "afterCommit"))) {
            afterCommit.add(callback);
        }
    }

    @Override
    public void afterCompletion(Consumer<TxOutcome> callback) {
        checkOwner();
        Objects.requireNonNull(callback, "callback must not be null");
        if (!session.registerExternalAfterCompletion(outcome -> runQuietly(() -> callback.accept(outcome), "afterCompletion"))) {
            afterCompletion.add(callback);
        }
    }

    // ===== 生命周期 =====

    @Override
    public void commit() {
        checkOwner();
        if (!savepointAfterCommitStack.isEmpty()) {
            throw new IllegalTxStateException(TxState.ACTIVE, TxState.COMMITTED, java.util.Set.of(TxState.ACTIVE),
                    new IllegalStateException("Cannot call commit() directly inside a savepoint scope"));
        }
        if (isCompleted()) {
            throw new IllegalTxStateException(TxState.COMMITTED, TxState.COMMITTED, TxState.COMMITTED.allowedTransitions(),
                    new IllegalStateException("transaction already completed"));
        }
        closeStreams();
        try {
            session.exitCommit();
        } catch (UnexpectedRollbackException e) {
            complete(TxOutcome.ROLLED_BACK);
            throw e;
        } catch (SQLException e) {
            complete(TxOutcome.UNKNOWN);
            throw new TxException("Commit failed.", e);
        } catch (IllegalTxStateException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            complete(TxOutcome.UNKNOWN);
            throw e;
        }
        complete(TxOutcome.COMMITTED);
    }

    @Override
    public void rollback() {
        checkOwner();
        if (!savepointAfterCommitStack.isEmpty()) {
            throw new IllegalTxStateException(TxState.ACTIVE, TxState.ROLLED_BACK, java.util.Set.of(TxState.ACTIVE),
                    new IllegalStateException("Cannot call rollback() directly inside a savepoint scope"));
        }
        if (isCompleted()) {
            return;
        }
        closeStreams();
        try {
            if (!session.isClosed() && session.isActive()) {
                session.exitRollback();
            }
        } catch (SQLException e) {
            complete(TxOutcome.UNKNOWN);
            throw new TxException("Rollback failed.", e);
        } catch (IllegalTxStateException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            complete(TxOutcome.UNKNOWN);
            throw e;
        }
        complete(TxOutcome.ROLLED_BACK);
    }

    @Override
    public void setRollbackOnly() {
        checkOwner();
        session.markRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        return session.isRollbackOnly();
    }

    @Override
    public void close() {
        checkOwner();
        closeStreams();
        try {
            session.close();
        } catch (SQLException e) {
            completeAfterCloseFailure();
            throw new TxException("Failed to close transaction connection.", e);
        } catch (IllegalTxStateException e) {
            throw e;
        } catch (RuntimeException e) {
            completeAfterCloseFailure();
            throw e;
        } catch (Error e) {
            completeAfterCloseFailure();
            throw e;
        }
        TxOutcome terminalOutcome = session.terminalOutcome();
        complete(terminalOutcome != null ? terminalOutcome : TxOutcome.ROLLED_BACK);
    }

    // ===== 内部 =====

    private void complete(TxOutcome outcome) {
        if (this.outcome != null) {
            return;
        }
        this.outcome = outcome;
        if (outcome == TxOutcome.COMMITTED) {
            for (Runnable callback : afterCommit) {
                runQuietly(callback, "afterCommit");
            }
        }
        for (Consumer<TxOutcome> callback : afterCompletion) {
            runQuietly(() -> callback.accept(outcome), "afterCompletion");
        }
    }

    private void completeAfterCloseFailure() {
        TxOutcome terminalOutcome = session.terminalOutcome();
        complete(terminalOutcome != null ? terminalOutcome : TxOutcome.UNKNOWN);
    }

    private void closeStreams() {
        for (Runnable closeAction : streamCloseActions) {
            try {
                closeAction.run();
            } catch (Throwable t) {
                log.warn("Transaction Stream cleanup failed callback", t);
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

    private final class TransactionalConnectionProvider implements ConnectionProvider, ResultSetSpliterator.StreamAccess {
        private final TxSession delegate;

        private TransactionalConnectionProvider(TxSession delegate) {
            this.delegate = delegate;
        }

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
            return delegate.queryTimeoutSeconds();
        }

        @Override
        public void checkStreamAccess() {
            checkOwner();
            if (outcome != null || session.isClosed() || !session.isActive()) {
                throw new TxException("Cannot read a transactional Stream after the transaction is no longer active"
                        + (outcome == null ? "." : " (outcome=" + outcome + ")."));
            }
        }

        @Override
        public void registerStream(Runnable closeAction) {
            openTransactionalStreams++;
            streamCloseActions.add(closeAction);
        }

        @Override
        public void onStreamClosed() {
            if (openTransactionalStreams > 0) {
                openTransactionalStreams--;
            }
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
