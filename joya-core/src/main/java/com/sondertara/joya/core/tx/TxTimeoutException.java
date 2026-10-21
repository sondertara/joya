package com.sondertara.joya.core.tx;

/**
 * 事务超时：超过 {@link TxStarter#timeoutSeconds(int)} 设定的截止时间后，
 * 事务内后续语句一律拒绝执行，事务被标记为仅回滚。
 */
public class TxTimeoutException extends TxException {

    public TxTimeoutException(String message) {
        super(message);
    }
}
