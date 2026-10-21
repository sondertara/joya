package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.proxy.JdkMapperProxy;
import com.sondertara.joya.core.query.dsl.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link Tx} 的单连接实现：直接绑定一条 {@link TxConnection} 与一份事务态 {@link JoyaTemplate}。
 */
final class TxImpl implements Tx {

    private static final Logger log = LoggerFactory.getLogger(TxImpl.class);

    private final TxConnection txConnection;
    private final JoyaTemplate template;
    private final List<Runnable> afterCommit = new ArrayList<>();
    private final List<Consumer<TxOutcome>> afterCompletion = new ArrayList<>();
    private boolean completed;

    TxImpl(JoyaTemplate rootTemplate, TxConnection txConnection) {
        this.txConnection = Objects.requireNonNull(txConnection, "txConnection must not be null");
        this.template = Objects.requireNonNull(rootTemplate, "rootTemplate must not be null").bind(txConnection);
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
    @SuppressWarnings("rawtypes")
    public <M extends JoyaMapper> M mapper(Class<M> mapperType) {
        Objects.requireNonNull(mapperType, "mapperType must not be null");
        if (!mapperType.isInterface()) {
            throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
        }
        return new JdkMapperProxy(this).create(mapperType);
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
        Objects.requireNonNull(body, "savepointCall body must not be null");
        try {
            txConnection.enterSavepoint();
        } catch (SQLException e) {
            throw new TxException("Failed to create savepoint", e);
        }
        R result;
        try {
            result = body.apply(this);
        } catch (Throwable t) {
            try {
                txConnection.exitSavepointRollback();
            } catch (SQLException rollbackFailure) {
                t.addSuppressed(rollbackFailure);
            }
            throw rethrow(t);
        }
        try {
            txConnection.exitSavepointCommit();
        } catch (SQLException e) {
            throw new TxException("Failed to release savepoint", e);
        }
        return result;
    }

    // ===== 回调 =====

    @Override
    public void afterCommit(Runnable callback) {
        afterCommit.add(Objects.requireNonNull(callback, "callback must not be null"));
    }

    @Override
    public void afterCompletion(Consumer<TxOutcome> callback) {
        afterCompletion.add(Objects.requireNonNull(callback, "callback must not be null"));
    }

    // ===== 生命周期 =====

    @Override
    public void commit() {
        if (completed) {
            throw new IllegalTxStateException(TxState.COMMITTED, TxState.COMMITTED, TxState.COMMITTED.allowedTransitions(),
                    new IllegalStateException("transaction already completed"));
        }
        try {
            txConnection.exitCommit();
        } catch (SQLException e) {
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
        try {
            if (!txConnection.isClosed() && txConnection.status().isActive()) {
                txConnection.exitRollback();
            }
        } catch (SQLException e) {
            complete(TxOutcome.ROLLED_BACK);
            throw new TxException("Rollback failed.", e);
        }
        complete(TxOutcome.ROLLED_BACK);
    }

    @Override
    public void setRollbackOnly() {
        txConnection.markRollbackOnly();
    }

    @Override
    public boolean isRollbackOnly() {
        return txConnection.status().globalRollbackOnly();
    }

    @Override
    public void close() {
        try {
            txConnection.close();
        } catch (SQLException e) {
            complete(TxOutcome.ROLLED_BACK);
            throw new TxException("Failed to close transaction connection.", e);
        }
        complete(TxOutcome.ROLLED_BACK);   // 已完成时为空操作
    }

    // ===== 内部 =====

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
