package com.sondertara.joya.core.tx;

/**
 * 无返回值的事务回调。
 */
@FunctionalInterface
public interface TxConsumer {

    void accept(Tx tx) throws Exception;
}
