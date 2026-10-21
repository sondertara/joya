package com.sondertara.joya.core.tx;

/** 事务结束结果，供 {@link Tx#afterCompletion} 回调使用。 */
public enum TxOutcome {
    /** 已提交 */
    COMMITTED,
    /** 已回滚 */
    ROLLED_BACK
}
