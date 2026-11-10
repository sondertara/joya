package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.DataOpsSupport;
import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.executor.ResultSetSpliterator;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.query.dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link Tx} 的统一实现：绑定一个底层 {@link TxSession}（纯 JDBC {@link TxConnection} 或 Spring 事务会话）
 * 与一份事务态 {@link ExecutionKernel}。
 */
public final class TxImpl implements Tx {

    private static final Logger log = LoggerFactory.getLogger(TxImpl.class);

    private final TxSession session;
    private final ExecutionKernel kernel;
    private final Thread ownerThread;
    private final DataOpsSupport dataOps;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Consumer<TxOutcome>> afterCompletion = new ArrayList<>();
    private final List<Runnable> streamCloseActions = new ArrayList<>();
    private final Deque<List<Runnable>> savepointAfterCommitStack = new ArrayDeque<>();
    private TxOutcome outcome;
    private int openTransactionalStreams;
    private boolean deferCompletionCallbacks;
    private boolean completionCallbacksFired;

    public TxImpl(ExecutionKernel rootKernel, TxSession session, EntityLifecycle entityLifecycle) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.ownerThread = Thread.currentThread();
        this.kernel = Objects.requireNonNull(rootKernel, "rootKernel must not be null")
                .bind(new TransactionalConnectionProvider(session));
        this.dataOps = DataOpsSupport.singleThread(this, kernel, entityLifecycle, this::checkActive);
    }

    public boolean isCompleted() {
        return outcome != null;
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
        return dataOps.from(entityType);
    }

    @Override
    public <T> EntityOps<T> of(Class<T> entityType) {
        return dataOps.of(entityType);
    }

    @Override
    public SqlOps sql(String sql, Object... args) {
        return dataOps.sql(sql, args);
    }

    @Override
    public SqlOps sql(SqlStatement statement) {
        return dataOps.sql(statement);
    }

    @Override
    public <M> M mapper(Class<M> mapperType) {
        return dataOps.mapper(mapperType);
    }

    @Override
    public <T> T withConnection(ConnectionCallback<T> action) {
        return dataOps.withConnection(action);
    }

    @Override
    public void script(Reader reader) {
        dataOps.script(reader);
    }

    @Override
    public JoyaSettings settings() {
        return dataOps.settings();
    }

    @Override
    public DbType dbType() {
        return dataOps.dbType();
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
        List<Runnable> frame = new ArrayList<>();
        try {
            session.enterSavepoint();
        } catch (SQLException e) {
            throw new TxException("Failed to create savepoint", e);
        }
        savepointAfterCommitStack.push(frame);
        R result;
        try {
            result = body.apply(this);
            if (!frame.isEmpty()) {
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
        savepointAfterCommitStack.pop();
        try {
            session.exitSavepointCommit();
        } catch (SQLException e) {
            session.markRollbackOnly();
            throw new TxException("Failed to release savepoint", e);
        } catch (RuntimeException | Error e) {
            session.markRollbackOnly();
            throw e;
        }
        for (Runnable cb : frame) {
            afterCommit(cb);
        }
        return result;
    }

    // ===== 回调 =====

    @Override
    public void afterCommit(Runnable callback) {
        checkActive();
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
        checkActive();
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
            throw new IllegalTxStateException(TxState.ACTIVE, TxState.COMMITTED, Set.of(TxState.ACTIVE),
                    new IllegalStateException("Cannot call commit() directly inside a savepoint scope"));
        }
        if (isCompleted()) {
            throw completedFailure(TxState.COMMITTED);
        }
        closeStreams();
        try {
            session.exitCommit();
        } catch (UnexpectedRollbackException | TxTimeoutException e) {
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
        closeSessionQuietly();
        complete(TxOutcome.COMMITTED);
    }

    @Override
    public void rollback() {
        checkOwner();
        if (!savepointAfterCommitStack.isEmpty()) {
            throw new IllegalTxStateException(TxState.ACTIVE, TxState.ROLLED_BACK, Set.of(TxState.ACTIVE),
                    new IllegalStateException("Cannot call rollback() directly inside a savepoint scope"));
        }
        if (isCompleted()) {
            throw completedFailure(TxState.ROLLED_BACK);
        }
        closeStreams();
        if (!session.isClosed() && !session.isActive() && session.terminalOutcome() == TxOutcome.COMMITTED) {
            complete(TxOutcome.COMMITTED);
            throw completedFailure(TxState.ROLLED_BACK);
        }
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
        checkActive();
        session.markRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        checkActive();
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

    void deferCompletionCallbacks(boolean defer) {
        this.deferCompletionCallbacks = defer;
    }

    void fireCompletionCallbacks() {
        if (completionCallbacksFired || this.outcome == null) {
            return;
        }
        completionCallbacksFired = true;
        TxOutcome finalOutcome = this.outcome;
        if (finalOutcome == TxOutcome.COMMITTED) {
            for (Runnable callback : List.copyOf(afterCommit)) {
                runQuietly(callback, "afterCommit");
            }
        }
        for (Consumer<TxOutcome> callback : List.copyOf(afterCompletion)) {
            runQuietly(() -> callback.accept(finalOutcome), "afterCompletion");
        }
    }

    private void closeSessionQuietly() {
        try {
            session.close();
        } catch (Throwable t) {
            log.warn("Failed to close transaction session after commit", t);
        }
    }

    private IllegalTxStateException completedFailure(TxState target) {
        TxState current = outcome == TxOutcome.COMMITTED ? TxState.COMMITTED : TxState.ROLLED_BACK;
        return new IllegalTxStateException(current, target, current.allowedTransitions(),
                new IllegalStateException("transaction already completed"));
    }

    private void complete(TxOutcome outcome) {
        if (this.outcome != null) {
            return;
        }
        this.outcome = outcome;
        if (!deferCompletionCallbacks) {
            fireCompletionCallbacks();
        }
    }

    private void completeAfterCloseFailure() {
        TxOutcome terminalOutcome = session.terminalOutcome();
        complete(terminalOutcome != null ? terminalOutcome : TxOutcome.UNKNOWN);
    }

    private void closeStreams() {
        List<Runnable> snapshot = List.copyOf(streamCloseActions);
        streamCloseActions.clear();
        for (Runnable closeAction : snapshot) {
            try {
                closeAction.run();
            } catch (Throwable t) {
                log.warn("Transaction Stream cleanup failed callback", t);
            }
        }
    }

    private void checkOwner() {
        TxSessionSupport.checkOwner(ownerThread);
    }

    private void checkActive() {
        checkOwner();
        if (isCompleted() || completionCallbacksFired || session.isClosed() || !session.isActive()) {
            throw completedFailure(TxState.ACTIVE);
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
        public void discard(Connection connection) throws SQLException {
            // 事务连接由 TxSession 生命周期管理，不在此处直接关闭物理连接
            delegate.release(connection);
        }

        @Override
        public OptionalInt queryTimeoutSeconds() {
            return delegate.queryTimeoutSeconds();
        }

        @Override
        public boolean allowsTransactionControl() {
            return false;
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

        @Override
        public void onStreamClosed(Runnable closeAction) {
            onStreamClosed();
            streamCloseActions.remove(closeAction);
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
