package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

/**
 * 物理事务绑定：一个 {@code TxConnection} = 一条物理连接 + 一份状态机。
 *
 * <p>核心不变量：
 * <ul>
 *   <li><b>I2 显式状态</b>：事务存在性由 {@link TxStatus} 表达，
 *       <b>绝不</b>从 {@code connection.getAutoCommit()} 反推。</li>
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

    /** 所有者线程：虚拟线程下尤其重要，跨线程使用必须响亮失败（I9）。 */
    private final Thread ownerThread;

    private final long startedAtNanos = System.nanoTime();

    private RestorePoint restorePoint;
    private TxStatus status = TxStatus.initial();
    /** 保存点栈：支持 savepoint 内再开 savepoint。 */
    private final Deque<Savepoint> savepoints = new ArrayDeque<>();
    /** 截止时间（纳秒）；0 表示不限时。 */
    private long deadlineNanos;
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
        this.ownerThread = Thread.currentThread();
    }

    // ===== 生命周期 =====

    /**
     * 开启物理事务：捕获还原点（I4）、施加选项、启动超时计时。
     */
    public void begin(TxOptions options) throws SQLException {
        checkOwner();
        if (status.state() != TxState.NONE) {
            throw new IllegalTxStateException(status.state(), TxState.ACTIVE, status.state().allowedTransitions());
        }
        restorePoint = RestorePoint.capture(connection);
        beginIncomplete = true;
        applyOptions(options);
        if (options.timeout() != null) {
            deadlineNanos = System.nanoTime() + options.timeout().toNanos();
        }
        status = status.transitionTo(TxState.ACTIVE).enter();
        beginIncomplete = false;
    }

    /**
     * 提交物理事务；若期间被标记仅回滚，则转为回滚并抛 {@link UnexpectedRollbackException}（I7）。
     */
    public void exitCommit() throws SQLException {
        checkOwner();
        status = status.exit();
        if (!status.isOutermost()) {
            return;
        }
        if (status.globalRollbackOnly()) {
            doRollback();
            throw new UnexpectedRollbackException(
                    "Transaction was marked rollback-only; commit refused (invariant I7).");
        }
        doCommit();
    }

    /**
     * 回滚物理事务。
     */
    public void exitRollback() throws SQLException {
        checkOwner();
        status = status.exit();
        if (!status.isOutermost()) {
            status = status.markRollbackOnly();
            return;
        }
        if (status.isActive()) {
            doRollback();
        }
    }

    /** 主动标记仅回滚（业务校验失败但不抛异常时使用）。 */
    public void markRollbackOnly() {
        checkOwner();
        if (status.isActive()) {
            status = status.markRollbackOnly();
        }
    }

    // ===== 保存点（Tx#savepoint / Tx#savepointCall） =====

    /** 创建保存点并进入一层。保存点创建成功后才增加深度，失败不留下半状态。 */
    public void enterSavepoint() throws SQLException {
        checkOwner();
        requireActive();
        savepoints.push(connection.setSavepoint());
        status = status.enter();
    }

    /** 成功退出保存点层：释放保存点，不影响外层物理事务。 */
    public void exitSavepointCommit() throws SQLException {
        checkOwner();
        Savepoint sp = savepoints.pop();
        status = status.exit();
        try {
            connection.releaseSavepoint(sp);
        } catch (SQLException | RuntimeException | Error e) {
            // 保存点写入是否仍有效已无法保证，禁止外层继续提交。
            status = status.markRollbackOnly();
            throw e;
        }
    }

    /** 失败退出保存点层：回滚到保存点；若保存点回滚自身失败，立即标记外层事务为仅回滚。 */
    public void exitSavepointRollback() throws SQLException {
        checkOwner();
        Savepoint sp = savepoints.pop();
        status = status.exit();
        try {
            connection.rollback(sp);
        } catch (SQLException | RuntimeException | Error e) {
            status = status.markRollbackOnly();
            throw e;
        }
    }

    // ===== ConnectionProvider：事务视图借还连接 =====

    @Override
    public Connection acquire() {
        checkOwner();
        if (closed) {
            throw new IllegalTxStateException(status.state(), TxState.ACTIVE, status.state().allowedTransitions());
        }
        requireActive();
        checkDeadline();
        status = status.incrementStatements();
        return connection;
    }

    @Override
    public void release(Connection connection) {
        // 连接属于事务：只在 close() 时归还连接池
    }

    @Override
    public OptionalInt queryTimeoutSeconds() {
        if (deadlineNanos == 0) {
            return defaultQueryTimeoutSeconds.isPresent()
                    ? defaultQueryTimeoutSeconds
                    : (baseConnections != null ? baseConnections.queryTimeoutSeconds() : OptionalInt.empty());
        }
        long remaining = deadlineNanos - System.nanoTime();
        // JDBC 超时单位为秒且 0 表示不限，向上取整并至少为 1
        return OptionalInt.of((int) Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L));
    }

    private void checkDeadline() {
        if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos) {
            status = status.markRollbackOnly();
            throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
        }
    }

    // ===== 状态查询 =====

    public TxStatus status() {
        return status;
    }

    @Override
    public boolean isRollbackOnly() {
        return status.globalRollbackOnly();
    }

    @Override
    public boolean isActive() {
        return status.isActive();
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
        if (status.state() == TxState.COMMITTED) {
            return TxOutcome.COMMITTED;
        }
        if (status.state() == TxState.ROLLED_BACK) {
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
        checkOwner();
        if (closed) {
            return;
        }
        closed = true;
        SQLException primaryError = null;
        try {
            // 未显式结束（包括 begin 选项设置失败）的事务一律回滚：安全默认，绝不隐式提交
            if ((status.isActive() || beginIncomplete) && !rollbackFailed) {
                try {
                    connection.rollback();
                    if (!commitFailed) {
                        physicalRollbackConfirmed = true;
                        status = status.transitionTo(TxState.ROLLED_BACK);
                    }
                    beginIncomplete = false;
                } catch (SQLException e) {
                    rollbackFailed = true;
                    primaryError = e;
                }
            }
        } finally {
            try {
                // 仅当提交/回滚未失败且事务已正常脱离 ACTIVE 状态时才还原 autoCommit，
                // 严禁在 rollback()/commit() 失败后调用 setAutoCommit(true) 导致未决事务被 JDBC 隐式提交！
                if (!commitFailed && !rollbackFailed && !status.isActive() && restorePoint != null) {
                    restorePoint.restore(connection);
                }
            } catch (SQLException restoreEx) {
                if (primaryError != null) {
                    primaryError.addSuppressed(restoreEx);
                } else {
                    primaryError = restoreEx;
                }
            } finally {
                try {
                    if (baseConnections != null && !commitFailed && !rollbackFailed && !status.isActive()) {
                        baseConnections.release(connection);
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

    private void applyOptions(TxOptions options) throws SQLException {
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
        if (options.hasExplicitIsolation()) {
            connection.setTransactionIsolation(options.isolation().getValue());
        }
        if (options.readOnly()) {
            connection.setReadOnly(true);
        }
    }

    private void doCommit() throws SQLException {
        try {
            connection.commit();
            status = status.transitionTo(TxState.COMMITTED);
        } catch (SQLException | RuntimeException | Error e) {
            commitFailed = true;
            throw e;
        }
    }

    private void doRollback() throws SQLException {
        try {
            connection.rollback();
            physicalRollbackConfirmed = true;
            status = status.transitionTo(TxState.ROLLED_BACK);
        } catch (SQLException | RuntimeException | Error e) {
            rollbackFailed = true;
            throw e;
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new TxException(
                    "Transaction is owned by thread [" + ownerThread.getName()
                            + "] but was used by [" + Thread.currentThread().getName()
                            + "]. A transaction must not cross threads (invariant I9).");
        }
    }

    private void requireActive() {
        if (!status.isActive()) {
            throw new IllegalTxStateException(status.state(), TxState.ACTIVE,
                    status.state().allowedTransitions());
        }
    }
}
