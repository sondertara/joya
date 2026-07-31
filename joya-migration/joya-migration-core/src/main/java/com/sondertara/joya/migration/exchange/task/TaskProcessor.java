
package com.sondertara.joya.migration.exchange.task;

import com.sondertara.joya.migration.exceptions.ExchangeException;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class TaskProcessor<R extends TaskResult> implements Supplier<R> {


    private Consumer<R> successCallback;

    private Consumer<ExchangeException> errorCallback;

    /**
     * 任务执行期间用于检查是否接收到任务中断信号
     */
    protected void checkInterrupt() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("task is interrupted");
        }
    }

    /**
     * 前置处理准备
     */
    protected void beforeProcess() {

    }

    /**
     * 处理过程
     *
     * @return R extends TaskResult
     */
    protected abstract R doProcess();

    /**
     * 异常处理
     *
     * @param t
     */
    protected abstract R exceptProcess(Throwable t);

    /**
     * 后置处理
     */
    protected void afterProcess() {

    }

    public void onSuccess(Consumer<R> successCallback) {
        this.successCallback = successCallback;
    }

    public void onError(Consumer<ExchangeException> errorCallback) {
        this.errorCallback = errorCallback;
    }


    @Override
    public R get() {
        try {
            checkInterrupt();
            beforeProcess();
            checkInterrupt();
            R result = doProcess();
            if (null != successCallback) {
                successCallback.accept(result);
            }
            return result;
        } catch (Throwable t) {
            if (null != errorCallback) {
                if (t instanceof ExchangeException) {
                    errorCallback.accept((ExchangeException) t);
                } else {
                    errorCallback.accept(new ExchangeException(t));
                }
            }
            return exceptProcess(t);
        } finally {
            afterProcess();
        }
    }

}
