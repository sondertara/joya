/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.transaction;

import com.sondertara.joya.core.jdbc.support.ConnectionContext;
import com.sondertara.joya.core.jdbc.support.TransactionSynchronization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingDeque;

import static com.sondertara.joya.core.jdbc.transaction.Propagation.*;

/**
 * 单数据源本地事务管理器。
 * <p>
 * 通过 {@link #tStatusStack} 管理事务嵌套栈，配合
 * {@link TransactionSynchronization} 的线程级连接绑定完成
 * 7 种传播行为。
 *
 * <h3>引用计数约定</h3>
 * <ul>
 *   <li>每次 {@link #begin} 均 {@code acquireContext} → 对顶层 ctx
 *   retain 一次</li>
 *   <li>每次 {@link #commit}/{@link #rollback} 在清理阶段
 *   ({@link #cleanupAfterCompletion}) release 一次</li>
 *   <li>REQUIRES_NEW 挂起时退出外层 ctx 线程绑定但不 release，
 *   resume 时放回绑定也不 release（后者由外层的 cleanup 负责）</li>
 *   <li>嵌套事务（NESTED / REQUIRED 加入）共享同一 ctx 的
 *   retain —— 每 begin 一次 retain，每 commit/rollback 一次 release，
 *   栈上下文中 matched</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public final class LocalTransactionManager implements TransactionManager {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTransactionManager.class);

    private final Deque<AbstractTransactionStatus> tStatusStack = new LinkedBlockingDeque<>();
    private final DataSource dataSource;

    public LocalTransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public TransactionStatus begin(Propagation propagation, Isolation isolation) throws SQLException {
        Objects.requireNonNull(propagation, "propagation must not be null");

        AbstractTransactionStatus status = new AbstractTransactionStatus(propagation, isolation);
        status.setConnectionContext(acquireContext(status));
        tStatusStack.addFirst(status);

        if (isExistingTransaction(status)) {
            switch (propagation) {
                case REQUIRES_NEW:
                    suspend(status);
                    doBegin(status);
                    break;
                case NESTED:
                    status.markSavepoint();
                    break;
                case NOT_SUPPORTED:
                    suspend(status);
                    break;
                case NEVER:
                    cleanupAfterCompletion(status);
                    throw new SQLException("existing transaction found for propagation 'NEVER'");
                default:
                    // REQUIRED / SUPPORTS / MANDATORY → 加入，无额外操作
                    break;
            }
        } else {
            switch (propagation) {
                case REQUIRED:
                case REQUIRES_NEW:
                case NESTED:
                    doBegin(status);
                    break;
                case MANDATORY:
                    cleanupAfterCompletion(status);
                    throw new SQLException("no existing transaction found for propagation 'MANDATORY'");
                default:
                    // SUPPORTS / NOT_SUPPORTED / NEVER → 非事务执行
                    break;
            }
        }
        return status;
    }

    private boolean isExistingTransaction(AbstractTransactionStatus status) {
        return status.getConnectionContext().hasTransaction();
    }

    // ============ commit ============

    @Override
    public void commit(TransactionStatus status) throws SQLException {
        AbstractTransactionStatus ats = cast(status);
        if (ats.isCompleted()) {
            throw new SQLException("Transaction is already completed – do not call commit or rollback more than once");
        }
        if (ats.isReadOnly() || ats.isRollbackOnly()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Transactional code has requested rollback");
            }
            rollback(ats);
            return;
        }
        try {
            unwindTo(ats);
            if (ats.hasSavepoint()) {
                ats.releaseSavepoint();
            } else if (ats.isNewConnection()) {
                doCommit(ats);
            }
        } catch (SQLException ex) {
            doRollback(ats);
            throw ex;
        } finally {
            cleanupAfterCompletion(ats);
        }
    }

    // ============ rollback ============

    @Override
    public void rollback(TransactionStatus status) throws SQLException {
        AbstractTransactionStatus ats = cast(status);
        if (ats.isCompleted()) {
            throw new SQLException("Transaction is already completed – do not call commit or rollback more than once");
        }
        try {
            unwindTo(ats);
            if (ats.hasSavepoint()) {
                ats.rollbackToSavepoint();
            } else if (ats.isNewConnection()) {
                doRollback(ats);
            }
        } catch (SQLException ex) {
            doRollback(ats);
            throw ex;
        } finally {
            cleanupAfterCompletion(ats);
        }
    }

    // ============ stack management ============

    @Override
    public boolean hasTransaction() {
        return !tStatusStack.isEmpty();
    }

    @Override
    public boolean isTopTransaction(TransactionStatus status) {
        if (tStatusStack.isEmpty() || status == null) {
            return false;
        }
        return tStatusStack.peek() == status;
    }

    /**
     * 返回栈顶状态（内部用）。
     */
    public AbstractTransactionStatus lastTransaction() {
        return tStatusStack.peek();
    }

    // ============ internal helpers ============

    private static AbstractTransactionStatus cast(TransactionStatus status) {
        if (status instanceof AbstractTransactionStatus) {
            return (AbstractTransactionStatus) status;
        }
        throw new IllegalArgumentException("Unsupported TransactionStatus implementation: " + status.getClass());
    }

    /**
     * 将栈弹至 {@code target} 位于栈顶。
     */
    private void unwindTo(AbstractTransactionStatus target) throws SQLException {
        if (!tStatusStack.contains(target)) {
            throw new SQLException("This transaction is not derived from this Manager.");
        }
        while (tStatusStack.peek() != target) {
            TransactionStatus top = tStatusStack.peek();
            if (top.isReadOnly() || top.isRollbackOnly()) {
                rollback(top);
            } else {
                commit(top);
            }
        }
    }

    // ============ connection context ============

    /**
     * 获取（或创建）线程绑定的 {@link ConnectionContext}，并增加引用计数。
     */
    private ConnectionContext acquireContext(AbstractTransactionStatus status) throws SQLException {
        ConnectionContext ctx = TransactionSynchronization.getOrCreate(dataSource);
        try {
            if (!ctx.isOpen() || !ctx.hasTransaction()) {
                status.markNewConnection();
            }
            ctx.retain();

            if (status.getIsolationLevel() != null && !status.getIsolationLevel().isDefault()) {
                Connection conn = ctx.getConnection();
                int currentIso = conn.getTransactionIsolation();
                int targetIso = status.getIsolationLevel().getValue();
                if (currentIso != targetIso) {
                    conn.setTransactionIsolation(targetIso);
                    ctx.setOriginalIsolation(Isolation.fromJdbcValue(currentIso));
                } else {
                    ctx.setOriginalIsolation(null);
                }
            } else {
                ctx.setOriginalIsolation(null);
            }
            return ctx;
        } catch (Exception e) {
            try {
                ctx.release();
            } catch (SQLException ignored) {
                // ignore release failure during error path
            }
            throw (e instanceof SQLException) ? (SQLException) e : new SQLException(e);
        }
    }

    // ============ life-cycle hooks ============

    private void doBegin(AbstractTransactionStatus status) throws SQLException {
        status.getConnectionContext().beginTransaction();
    }

    private void doCommit(AbstractTransactionStatus status) throws SQLException {
        ConnectionContext ctx = status.getConnectionContext();
        if (ctx.hasTransaction()) {
            ctx.getConnection().commit();
        }
    }

    private void doRollback(AbstractTransactionStatus status) throws SQLException {
        ConnectionContext ctx = status.getConnectionContext();
        if (ctx.hasTransaction()) {
            ctx.getConnection().rollback();
        }
    }

    // ============ suspend / resume ============

    /**
     * 挂起外层事务：将当前线程绑定的 {@link ConnectionContext}
     * 从 {@link TransactionSynchronization} 中移除，保存在 status 内。
     * <p>
     * <em>不 release 外层 ctx</em> —— 外层事务结束后由 cleanup 负责 release。
     */
    private void suspend(AbstractTransactionStatus status) throws SQLException {
        if (status.isSuspend()) {
            throw new SQLException("Transaction is already suspended.");
        }
        if (!isTopTransaction(status)) {
            throw new SQLException("Only the top transaction can be suspended.");
        }
        ConnectionContext outerCtx = status.getConnectionContext();
        status.setSuspendedContext(outerCtx);
        status.markSuspend();

        // 从线程绑定移除外层 ctx
        TransactionSynchronization.suspend(dataSource);

        // 为新事务 acquire 新的 ctx（retain +1）
        ConnectionContext newCtx = acquireContext(status);
        status.setConnectionContext(newCtx);
    }

    /**
     * 恢复被挂起的事务：在内层事务 cleanup 后将外层 ctx 放回线程绑定。
     * <p>
     * 注意：此方法由 {@link #cleanupAfterCompletion} 在 status.suspend 时调用，
     * 调用前内层事务已 release 其 ctx。外层 ctx 不在此处 release，
     * 而是由外层事务走完 commit/rollback 后的 cleanup 统一 release。
     */
    private void resume(AbstractTransactionStatus status) throws SQLException {
        if (!status.isCompleted()) {
            throw new SQLException("Cannot resume a transaction that has not completed.");
        }
        if (!status.isSuspend()) {
            throw new SQLException("Transaction was not suspended.");
        }

        ConnectionContext outerCtx = status.getSuspendedContext();
        TransactionSynchronization.resume(dataSource, outerCtx);
        status.setConnectionContext(outerCtx);
        status.setSuspendedContext(null);
    }

    // ============ cleanup ============

    /**
     * 提交/回滚后清理：恢复隔离级别、结束事务标记、release 引用计数、
     * 弹出栈、恢复挂起的外层事务。
     */
    private void cleanupAfterCompletion(AbstractTransactionStatus status) throws SQLException {
        if (!isTopTransaction(status)) {
            throw new SQLException("Cannot clean up a transaction that is not at the top of the stack.");
        }

        status.setCompleted();

        // 恢复隔离级别
        ConnectionContext ctx = status.getConnectionContext();
        Isolation original = ctx.getOriginalIsolation();
        if (original != null) {
            ctx.getConnection().setTransactionIsolation(original.getValue());
        }
        ctx.endTransaction();

        ctx.release(); // balance the retain in acquireContext

        // 恢复挂起的外层事务
        if (status.isSuspend()) {
            resume(status);
        }

        Deque<AbstractTransactionStatus> stack = this.tStatusStack;
        if (!stack.isEmpty() && stack.peek() == status) {
            stack.removeFirst();
        }

        status.setConnectionContext(null);
        status.setSuspendedContext(null);
    }
}
