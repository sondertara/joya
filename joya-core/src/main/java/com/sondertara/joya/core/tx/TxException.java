package com.sondertara.joya.core.tx;

/**
 * 事务异常基类。
 */
public class TxException extends RuntimeException {

    public TxException(String message) {
        super(message);
    }

    public TxException(String message, Throwable cause) {
        super(message, cause);
    }
}
