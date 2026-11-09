package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.ExecutionKernel;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

/**
 * 事务会话公共支撑组件（供 {@link TxConnection}、{@code SpringTxSession} 及 {@link TxImpl} 组合复用）：
 * <ul>
 *   <li><b>所有者线程独占守卫（不变量 I9）</b>：统一在跨线程访问时快速失败；</li>
 *   <li><b>截止时间与语句级超时换算</b>：统一维护 {@code deadlineNanos} 及向上取整的剩余秒数；</li>
 *   <li><b>保存点栈</b>：统一管理嵌套保存点压栈与弹栈。</li>
 * </ul>
 *
 * @param <S> 底层保存点句柄类型（JDBC {@link java.sql.Savepoint} 或 Spring 保存点对象）
 */
public final class TxSessionSupport<S> {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Thread ownerThread = Thread.currentThread();
    private final Deque<S> savepoints = new ArrayDeque<>();
    private long deadlineNanos;

    /** 校验当前调用线程是否为事务所有者线程（不变量 I9）。 */
    public void checkOwner() {
        checkOwner(ownerThread);
    }

    /** 静态所有者线程校验（供不持有会话栈的 {@link TxImpl} 复用）。 */
    public static void checkOwner(Thread ownerThread) {
        if (Thread.currentThread() != ownerThread) {
            throw new TxException("Transaction is owned by thread [" + ownerThread.getName()
                    + "] but was used by [" + Thread.currentThread().getName()
                    + "]. A transaction must not cross threads (invariant I9).");
        }
    }

    /** 启动或重置事务截止时间计时（{@code timeout == null} 表示不限时）。 */
    public void startDeadline(Duration timeout) {
        this.deadlineNanos = timeout == null ? 0L : System.nanoTime() + timeout.toNanos();
    }

    /** 判断事务是否已超过截止时间。 */
    public boolean isDeadlineExpired() {
        return deadlineNanos != 0L && System.nanoTime() > deadlineNanos;
    }

    /**
     * 校验截止时间；若已超时则先执行 {@code onExpired}（如标记仅回滚）再抛 {@link TxTimeoutException}。
     */
    public void checkDeadline(Runnable onExpired) {
        if (isDeadlineExpired()) {
            if (onExpired != null) {
                onExpired.run();
            }
            throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
        }
    }

    /**
     * 计算当前语句级超时秒数：若有事务 deadline 则按剩余纳秒向上取整（至少 1 秒），否则回退到 {@code fallback}。
     */
    public OptionalInt resolveQueryTimeoutSeconds(OptionalInt fallback) {
        if (deadlineNanos != 0L) {
            long remaining = deadlineNanos - System.nanoTime();
            return OptionalInt.of((int) Math.max(1L, (remaining + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND));
        }
        return fallback != null ? fallback : OptionalInt.empty();
    }

    /** 将 {@link Duration} 向上取整为至少 1 秒的 JDBC / Spring 事务超时秒数。 */
    public static int ceilSeconds(Duration timeout) {
        return (int) Math.max(1L, (timeout.toNanos() + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND);
    }

    /** 从根内核解析非限时事务的默认语句超时秒数（优先连接提供者，其次 {@code ExecutionOptions}）。 */
    public static OptionalInt resolveDefaultQueryTimeout(ExecutionKernel rootKernel) {
        OptionalInt providerTimeout = rootKernel.connections().queryTimeoutSeconds();
        if (providerTimeout != null && providerTimeout.isPresent()) {
            return providerTimeout;
        }
        int configuredTimeout = rootKernel.executionOptions().queryTimeoutSeconds();
        return configuredTimeout > 0 ? OptionalInt.of(configuredTimeout) : OptionalInt.empty();
    }

    /** 压入一层保存点。 */
    public void pushSavepoint(S savepoint) {
        savepoints.push(savepoint);
    }

    /** 弹出最近一层保存点。 */
    public S popSavepoint() {
        return savepoints.pop();
    }

    /** 是否处于保存点嵌套作用域内。 */
    public boolean hasSavepoint() {
        return !savepoints.isEmpty();
    }
}
