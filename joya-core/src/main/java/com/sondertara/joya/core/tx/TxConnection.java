package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.OptionalInt;

/**
 * 物理事务绑定：一个 {@code TxConnection} = 一条物理连接 + 一份状态机。
 *
 * <p>核心不变量：
 * <ul>
 *   <li><b>I2 显式状态</b>：事务存在性由 {@link TxState} 表达，<b>绝不</b>从 {@code connection.getAutoCommit()} 反推。</li>
 *   <li><b>I4 还原点只捕获一次</b>：开启事务时捕获 {@code autoCommit / isolation / readOnly}，关闭时还原。</li>
 *   <li><b>I5 连接不可替换</b>：{@code connection} 是 {@code final}，事务期内不调 {@code isValid()}。</li>
 *   <li><b>I6 close 幂等</b>：归还连接池恰好一次。</li>
 *   <li><b>I7 仅回滚守卫</b>：标记为 rollback-only 后拒绝 commit，抛 {@link UnexpectedRollbackException}。</li>
 *   <li><b>I9 所有者线程独占</b>：跨线程使用抛异常，而非静默错乱。</li>
 * </ul>
 */
public final class TxConnection implements TxSession {

    /** final —— 结构上保证事务期内连接不可替换（I5）。 */
    private final Connection connection;

    /** 可选的底层连接提供者（用于归还自定义连接）。 */
    private final ConnectionProvider baseConnections;

    /** 全局默认查询超时秒数（无事务 deadline 时回退使用）。 */
    private final OptionalInt defaultQueryTimeoutSeconds;

    /** 会话公共支撑：所有者线程守卫（I9）、截止时间计时与嵌套保存点栈。 */
    private final TxSessionSupport<Savepoint> support = new TxSessionSupport<>();

    private final long startedAtNanos = System.nanoTime();

    private RestorePoint restorePoint;
    private TxState state = TxState.NONE;
    private boolean globalRollbackOnly;
    private boolean commitFailed;
    private boolean rollbackFailed;
    private boolean physicalRollbackConfirmed;
    private boolean beginIncomplete;
    private boolean closed;

    public TxConnection(Connection connection) {
        this(connection, null, OptionalInt.empty());
    }

    public TxConnection(Connection connection, ConnectionProvider baseConnections) {
        this(connection, baseConnections,
                baseConnections != null ? baseConnections.queryTimeoutSeconds() : OptionalInt.empty());
    }

    public TxConnection(Connection connection, ConnectionProvider baseConnections, OptionalInt defaultQueryTimeoutSeconds) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        this.connection = connection;
        this.baseConnections = baseConnections;
        this.defaultQueryTimeoutSeconds = defaultQueryTimeoutSeconds != null ? defaultQueryTimeoutSeconds : OptionalInt.empty();
    }

    // ===== 生命周期 =====

    /**
     * 开启物理事务：捕获还原点（I4）、施加选项、启动超时计时。
     */
    public void begin(TxOptions options) throws SQLException {
        support.checkOwner();
        if (state != TxState.NONE) {
            throw new IllegalTxStateException(state, TxState.ACTIVE, state.allowedTransitions());
        }
        restorePoint = RestorePoint.capture(connection);
        beginIncomplete = true;
        applyOptions(options);
        support.startDeadline(options.timeout());
        transitionTo(TxState.ACTIVE);
        beginIncomplete = false;
    }

    /**
     * 提交物理事务；若期间被标记仅回滚，则转为回滚并抛 {@link UnexpectedRollbackException}（I7）。
     */
    @Override
    public void exitCommit() throws SQLException {
        support.checkOwner();
        if (support.hasSavepoint()) {
            return;
        }
        if (globalRollbackOnly) {
            doRollback();
            throw new UnexpectedRollbackException(
                    "Transaction was marked rollback-only; commit refused (invariant I7).");
        }
        if (support.isDeadlineExpired()) {
            markRollbackOnlyState();
            doRollback();
            throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
        }
        doCommit();
    }

    /**
     * 回滚物理事务。
     */
    @Override
    public void exitRollback() throws SQLException {
        support.checkOwner();
        if (support.hasSavepoint()) {
            markRollbackOnlyState();
            return;
        }
        if (isActive()) {
            doRollback();
        }
    }

    /** 主动标记仅回滚（业务校验失败但不抛异常时使用）。 */
    @Override
    public void markRollbackOnly() {
        support.checkOwner();
        if (isActive()) {
            markRollbackOnlyState();
        }
    }

    // ===== 保存点（Tx#savepoint / Tx#savepointCall） =====

    /** 创建保存点并进入一层。保存点栈长度即为唯一深度真相源。 */
    @Override
    public void enterSavepoint() throws SQLException {
        support.checkOwner();
        requireActive();
        support.pushSavepoint(connection.setSavepoint());
    }

    /** 成功退出保存点层：释放保存点，不影响外层物理事务。 */
    @Override
    public void exitSavepointCommit() throws SQLException {
        support.checkOwner();
        Savepoint sp = support.popSavepoint();
        try {
            connection.releaseSavepoint(sp);
        } catch (SQLFeatureNotSupportedException ignored) {
            // 部分数据库驱动（如 Oracle / SQL Server）不支持显式释放保存点，保存点将在外层事务结束时自动释放
        } catch (SQLException | RuntimeException | Error e) {
            markRollbackOnlyState();
            throw e;
        }
    }

    /** 失败退出保存点层：回滚到保存点；若保存点回滚自身失败，立即标记外层事务为仅回滚。 */
    @Override
    public void exitSavepointRollback() throws SQLException {
        support.checkOwner();
        Savepoint sp = support.popSavepoint();
        try {
            connection.rollback(sp);
        } catch (SQLException | RuntimeException | Error e) {
            markRollbackOnlyState();
            throw e;
        }
    }

    // ===== ConnectionProvider：事务视图借还连接 =====

    @Override
    public Connection acquire() {
        support.checkOwner();
        if (closed) {
            throw new IllegalTxStateException(state, TxState.ACTIVE, state.allowedTransitions());
        }
        requireActive();
        support.checkDeadline(this::markRollbackOnlyState);
        return connection;
    }

    @Override
    public void release(Connection connection) {
        // 连接属于事务：只在 close() 时归还连接池
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        OptionalInt fallback = defaultQueryTimeoutSeconds.isPresent()
                ? defaultQueryTimeoutSeconds
                : (baseConnections != null ? baseConnections.queryTimeoutSeconds() : OptionalInt.empty());
        return support.resolveQueryTimeoutSeconds(fallback);
    }

    // ===== 状态查询 =====

    @Override
    public boolean isRollbackOnly() {
        return globalRollbackOnly;
    }

    @Override
    public boolean isActive() {
        return state == TxState.ACTIVE || state == TxState.MARKED_ROLLBACK;
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public TxOutcome terminalOutcome() {
        if (state == TxState.COMMITTED) {
            return TxOutcome.COMMITTED;
        }
        if (state == TxState.ROLLED_BACK) {
            return TxOutcome.ROLLED_BACK;
        }
        return null;
    }

    @Override
    public boolean isPhysicalRollbackConfirmed() {
        return physicalRollbackConfirmed;
    }

    public long elapsedNanos() {
        return System.nanoTime() - startedAtNanos;
    }

    // ===== 关闭（I6 幂等） =====

    @Override
    public void close() throws SQLException {
        support.checkOwner();
        if (closed) {
            return;
        }
        closed = true;
        SQLException primaryError = null;
        try {
            // 未显式结束（包括 begin 选项设置失败）的事务一律回滚：安全默认，绝不隐式提交
            if ((isActive() || beginIncomplete) && !rollbackFailed) {
                try {
                    connection.rollback();
                    if (!commitFailed) {
                        physicalRollbackConfirmed = true;
                        transitionTo(TxState.ROLLED_BACK);
                    }
                    beginIncomplete = false;
                } catch (SQLException e) {
                    rollbackFailed = true;
                    primaryError = e;
                }
            }
        } finally {
            boolean restoreFailed = false;
            try {
                // 仅当提交/回滚未失败且事务已正常脱离 ACTIVE 状态时才还原 autoCommit，
                // 严禁在 rollback()/commit() 失败后调用 setAutoCommit(true) 导致未决事务被 JDBC 隐式提交！
                if (!commitFailed && !rollbackFailed && !isActive() && restorePoint != null) {
                    restorePoint.restore(connection);
                }
            } catch (SQLException restoreEx) {
                restoreFailed = true;
                if (primaryError != null) {
                    primaryError.addSuppressed(restoreEx);
                } else {
                    primaryError = restoreEx;
                }
            } finally {
                try {
                    if (baseConnections != null) {
                        if (!commitFailed && !rollbackFailed && !restoreFailed && !isActive()) {
                            baseConnections.release(connection);
                        } else {
                            baseConnections.discard(connection);
                        }
                    } else {
                        connection.close();   // 归还池或关闭污染连接
                    }
                } catch (SQLException closeEx) {
                    if (primaryError != null) {
                        primaryError.addSuppressed(closeEx);
                    } else {
                        primaryError = closeEx;
                    }
                }
            }
        }
        if (primaryError != null) {
            throw primaryError;
        }
    }

    // ===== 内部 =====

    private void transitionTo(TxState target) {
        state.assertTransitionTo(target);
        state = target;
    }

    private void markRollbackOnlyState() {
        if (state != TxState.MARKED_ROLLBACK) {
            transitionTo(TxState.MARKED_ROLLBACK);
        }
        globalRollbackOnly = true;
    }

    private void applyOptions(TxOptions options) throws SQLException {
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
        if (options.hasExplicitIsolation()) {
            connection.setTransactionIsolation(options.isolation().getValue());
        }
        if (options.readOnly()) {
            connection.setReadOnly(true);
        } else if (connection.isReadOnly()) {
            connection.setReadOnly(false);
        }
    }

    private void doCommit() throws SQLException {
        try {
            connection.commit();
            transitionTo(TxState.COMMITTED);
        } catch (SQLException | RuntimeException | Error e) {
            commitFailed = true;
            throw e;
        }
    }

    private void doRollback() throws SQLException {
        try {
            connection.rollback();
            physicalRollbackConfirmed = true;
            transitionTo(TxState.ROLLED_BACK);
        } catch (SQLException | RuntimeException | Error e) {
            rollbackFailed = true;
            throw e;
        }
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalTxStateException(state, TxState.ACTIVE, state.allowedTransitions());
        }
    }

    /**
     * 连接属性还原点（不变量 I4）：在事务最外层捕获一次连接的原始属性（autoCommit / 隔离级别 / readOnly），
     * 事务结束时逐项比对还原。
     */
    private record RestorePoint(boolean autoCommit, int isolation, boolean readOnly) {

        static RestorePoint capture(Connection connection) throws SQLException {
            return new RestorePoint(
                    connection.getAutoCommit(),
                    connection.getTransactionIsolation(),
                    connection.isReadOnly());
        }

        void restore(Connection connection) throws SQLException {
            SQLException primary = null;
            try {
                if (connection.getAutoCommit() != autoCommit) {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                primary = e;
            }
            try {
                if (connection.getTransactionIsolation() != isolation) {
                    connection.setTransactionIsolation(isolation);
                }
            } catch (SQLException e) {
                if (primary == null) {
                    primary = e;
                } else {
                    primary.addSuppressed(e);
                }
            }
            try {
                if (connection.isReadOnly() != readOnly) {
                    connection.setReadOnly(readOnly);
                }
            } catch (SQLException e) {
                if (primary == null) {
                    primary = e;
                } else {
                    primary.addSuppressed(e);
                }
            }
            if (primary != null) {
                throw primary;
            }
        }
    }
}
