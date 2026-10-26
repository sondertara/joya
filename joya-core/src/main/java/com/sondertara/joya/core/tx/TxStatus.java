package com.sondertara.joya.core.tx;

/**
 * 事务运行时状态 —— <b>不可变快照</b>，每次变更返回新实例。
 *
 * <p>把 {@code state} / {@code depth} / {@code globalRollbackOnly} 三个原本独立可变的字段
 * 收敛为一个整体，消除它们之间的不一致窗口。
 *
 * <p>{@code depth} 语义：嵌套事务深度。{@code depth == 0} 表示<b>无嵌套</b>，
 * 只有最外层（depth 减到 0 时）才允许执行物理提交/回滚（不变量 I3）。
 */
public record TxStatus(
        TxState state,
        int depth,
        boolean globalRollbackOnly,
        long startedAtNanos,
        int statementCount
) {

    public static TxStatus initial() {
        return new TxStatus(TxState.NONE, 0, false, 0L, 0);
    }

    /** 开启一层嵌套。 */
    public TxStatus enter() {
        return new TxStatus(state, depth + 1, globalRollbackOnly, startedAtNanos, statementCount);
    }

    /** 退出一层嵌套。 */
    public TxStatus exit() {
        return new TxStatus(state, Math.max(0, depth - 1), globalRollbackOnly, startedAtNanos, statementCount);
    }

    /** 标记全局仅回滚。 */
    public TxStatus markRollbackOnly() {
        return new TxStatus(TxState.MARKED_ROLLBACK, depth, true, startedAtNanos, statementCount);
    }

    /** 切换到指定状态（会走迁移断言）。 */
    public TxStatus transitionTo(TxState target) {
        state.assertTransitionTo(target);
        return new TxStatus(target, depth, globalRollbackOnly, startedAtNanos, statementCount);
    }

    /** 记录一次语句执行。 */
    public TxStatus incrementStatements() {
        return new TxStatus(state, depth, globalRollbackOnly, startedAtNanos, statementCount + 1);
    }

    /** 是否处于最外层（depth 归零）。仅此处允许物理提交/回滚。 */
    public boolean isOutermost() {
        return depth == 0;
    }

    /** 是否已开启（NONE 之外）。 */
    public boolean isActive() {
        return state == TxState.ACTIVE || state == TxState.MARKED_ROLLBACK;
    }
}
