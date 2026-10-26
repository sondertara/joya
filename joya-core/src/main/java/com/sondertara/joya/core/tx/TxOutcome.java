package com.sondertara.joya.core.tx;

/** 事务结束结果，供 {@link Tx#afterCompletion} 回调使用。 */
public enum TxOutcome {
    /** 已提交 */
    COMMITTED,
    /** 已回滚 */
    ROLLED_BACK,
    /** 结果未知（如提交或回滚自身发生底层故障，无法确认最终状态） */
    UNKNOWN
}
