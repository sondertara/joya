package com.sondertara.joya.core.tx;

/**
 * 事务回调。
 *
 * @param <R> 返回值类型
 */
@FunctionalInterface
public interface TxCallback<R> {

    /**
     * 在事务内执行。
     *
     * <p><b>重试语义</b>：若配置了重试（{@code TxStarter.retryOnDeadlock(n)}），
     * 本回调可能被<b>重复执行</b>，因此必须是幂等的。
     *
     * @param tx 事务句柄
     * @return 业务结果
     * @throws Exception 任意异常均触发回滚，且该异常会作为主异常抛出
     */
    R apply(Tx tx) throws Exception;
}
