package com.sondertara.joya.core.tx;

import java.util.Set;

/**
 * 非法的事务状态迁移 / 非法操作。
 *
 * <p>错误信息必须包含：当前状态、试图迁移到的状态、该状态下全部合法目标。
 * 排障时这决定是 5 分钟还是 5 小时。
 */
public class IllegalTxStateException extends TxException {

    private final TxState current;
    private final TxState target;
    private final Set<TxState> allowed;

    public IllegalTxStateException(TxState current, TxState target, Set<TxState> allowed) {
        this(current, target, allowed, null);
    }

    public IllegalTxStateException(TxState current, TxState target, Set<TxState> allowed, Throwable cause) {
        super(buildMessage(current, target, allowed), cause);
        this.current = current;
        this.target = target;
        this.allowed = allowed;
    }

    private static String buildMessage(TxState current, TxState target, Set<TxState> allowed) {
        return "Illegal transaction state transition: " + current + " -> " + target
                + ". Allowed from " + current + ": "
                + (allowed == null || allowed.isEmpty() ? "(none; terminal state)" : allowed);
    }

    public TxState current() {
        return current;
    }

    public TxState target() {
        return target;
    }

    public Set<TxState> allowed() {
        return allowed;
    }
}
