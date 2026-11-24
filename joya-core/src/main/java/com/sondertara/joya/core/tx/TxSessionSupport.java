package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

/**
 * 事务会话公共支撑组件（供 {@link TxConnection}、{@code SpringTxSession} 及 {@link TxImpl} 组合复用）：
 * <ul>
 *   <li><b>所有者线程独占守卫（不变量 I9）</b>：统一在跨线程访问时快速失败；</li>
 *   <li><b>单调时钟截止时间与语句级超时换算</b>：使用 {@code hasDeadline} 与二补数差值比较规避 {@code System.nanoTime()} 回绕及 {@code 0L} 哨兵碰撞，
 *       并在批量分片/脚本执行中途超时后立即触发 {@link TxTimeoutException}；</li>
 *   <li><b>保存点栈</b>：统一管理嵌套保存点压栈与弹栈。</li>
 * </ul>
 *
 * @param <S> 底层保存点句柄类型
 */
public final class TxSessionSupport<S> {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Thread ownerThread = Thread.currentThread();
    private final Deque<S> savepoints = new ArrayDeque<>();
    private boolean hasDeadline;
    private long deadlineNanos;

    public void checkOwner() {
        checkOwner(ownerThread);
    }

    public static void checkOwner(Thread ownerThread) {
        if (Thread.currentThread() != ownerThread) {
            throw new TxException("Transaction is owned by thread [" + ownerThread.getName()
                    + "] but was used by [" + Thread.currentThread().getName()
                    + "]. A transaction must not cross threads (invariant I9).");
        }
    }

    /** 启动或重置事务截止时间计时（{@code timeout == null} 表示不限时）。 */
    public void startDeadline(@Nullable Duration timeout) {
        if (timeout == null) {
            this.hasDeadline = false;
            this.deadlineNanos = 0L;
            return;
        }
        long deltaNanos;
        if (timeout.isNegative() || timeout.isZero()) {
            deltaNanos = 0L;
        } else {
            try {
                deltaNanos = timeout.toNanos();
            } catch (ArithmeticException ex) {
                deltaNanos = Long.MAX_VALUE;
            }
        }
        this.hasDeadline = true;
        this.deadlineNanos = System.nanoTime() + deltaNanos;
    }

    /** 基于二补数差值判定单调时钟是否已到达或超过截止时间（规避 {@code nanoTime} 符号位回绕与 {@code 0L} 碰撞）。 */
    public boolean isDeadlineExpired() {
        return hasDeadline && (System.nanoTime() - deadlineNanos >= 0L);
    }

    public void checkDeadline(@Nullable Runnable onExpired) {
        if (isDeadlineExpired()) {
            if (onExpired != null) {
                onExpired.run();
            }
            throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
        }
    }

    public OptionalInt resolveQueryTimeoutSeconds(OptionalInt fallback) {
        return resolveQueryTimeoutSeconds(fallback, null);
    }

    /**
     * 计算当前语句级剩余超时秒数：若已到达事务截止时间则立即触发 {@code onExpired} 并抛 {@link TxTimeoutException}，
     * 杜绝在超期后继续以 1 秒窗口执行后续批量分片或脚本语句。
     */
    public OptionalInt resolveQueryTimeoutSeconds(OptionalInt fallback, @Nullable Runnable onExpired) {
        if (hasDeadline) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                if (onExpired != null) {
                    onExpired.run();
                }
                throw new TxTimeoutException("Transaction timed out; it has been marked rollback-only.");
            }
            long ceilSec = (remaining / NANOS_PER_SECOND) + (remaining % NANOS_PER_SECOND == 0L ? 0L : 1L);
            return OptionalInt.of((int) Math.min(Integer.MAX_VALUE, Math.max(1L, ceilSec)));
        }
        return fallback != null ? fallback : OptionalInt.empty();
    }

    /** 将 {@link Duration} 安全向上取整为至少 1 秒的事务超时秒数（无 {@code toNanos()} 溢出风险）。 */
    public static int ceilSeconds(Duration timeout) {
        long secs = timeout.getSeconds();
        if (secs < 0L) {
            return 1;
        }
        if (timeout.getNano() > 0 && secs < Long.MAX_VALUE) {
            secs++;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, secs));
    }

    public static OptionalInt resolveDefaultQueryTimeout(ExecutionKernel rootKernel) {
        OptionalInt providerTimeout = rootKernel.connections().queryTimeoutSeconds();
        if (providerTimeout != null && providerTimeout.isPresent()) {
            return providerTimeout;
        }
        int configuredTimeout = rootKernel.executionOptions().queryTimeoutSeconds();
        return configuredTimeout > 0 ? OptionalInt.of(configuredTimeout) : OptionalInt.empty();
    }

    public void pushSavepoint(S savepoint) {
        savepoints.push(savepoint);
    }

    public S popSavepoint() {
        return savepoints.pop();
    }

    public boolean hasSavepoint() {
        return !savepoints.isEmpty();
    }
}
