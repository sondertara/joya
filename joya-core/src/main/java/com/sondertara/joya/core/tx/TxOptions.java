package com.sondertara.joya.core.tx;

import java.time.Duration;

/**
 * 事务选项 —— <b>不可变</b>，每个 {@code withXxx} 返回新实例，可安全共享复用。
 *
 * <pre>{@code
 * static final TxOptions REPORT = TxOptions.DEFAULT.withReadOnly(true).withTimeout(Duration.ofSeconds(30));
 * }</pre>
 *
 * <p>用户通常不直接接触本类，而是通过 {@link TxStarter} 的流式方法配置。
 *
 * <p>没有"传播行为"选项：事务是显式值，"加入外层"就是把外层 {@link Tx} 传下去，
 * "新开独立事务"就是再调一次 {@code tx().run(...)}；同一事务内的局部回滚用 {@link Tx#savepoint}。
 *
 * @param isolation     隔离级别；{@link Isolation#DEFAULT} 表示沿用连接默认值
 * @param readOnly      只读事务
 * @param timeout       事务超时；{@code null} 表示不限制。超时后后续语句抛 {@link TxTimeoutException}
 * @param retryAttempts 死锁 / 序列化失败（SQLState 40xxx）的最大尝试次数；{@code <=1} 表示不重试
 */
public record TxOptions(
        Isolation isolation,
        boolean readOnly,
        Duration timeout,
        int retryAttempts
) {

    public static final TxOptions DEFAULT = new TxOptions(Isolation.DEFAULT, false, null, 0);

    public TxOptions {
        isolation = isolation == null ? Isolation.DEFAULT : isolation;
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
    }

    public TxOptions withIsolation(Isolation isolation) {
        return new TxOptions(isolation, readOnly, timeout, retryAttempts);
    }

    public TxOptions withReadOnly(boolean readOnly) {
        return new TxOptions(isolation, readOnly, timeout, retryAttempts);
    }

    public TxOptions withTimeout(Duration timeout) {
        return new TxOptions(isolation, readOnly, timeout, retryAttempts);
    }

    public TxOptions withRetryAttempts(int retryAttempts) {
        return new TxOptions(isolation, readOnly, timeout, retryAttempts);
    }

    /** 是否需要在开启事务时主动设置隔离级别。 */
    public boolean hasExplicitIsolation() {
        return isolation != Isolation.DEFAULT;
    }
}
