package com.sondertara.joya.core.tx;

import java.sql.SQLException;

/**
 * 事务执行器 —— 异常合成（不变量 I8）与重试的<b>唯一收敛点</b>。
 *
 * <p>I8 异常合成规则：
 * <ul>
 *   <li><b>业务异常是主异常</b>：回滚失败、关闭失败一律 {@code addSuppressed}，绝不替换主异常。
 *       （旧实现 P1-4 正是用 {@code finally} 里的异常覆盖了业务异常，导致真实故障原因丢失。）</li>
 *   <li>{@code Error} 直接重抛，不包装。</li>
 * </ul>
 */
final class TxExecutor {

    private static final long BASE_RETRY_DELAY_MS = 10L;
    private static final long MAX_RETRY_DELAY_MS = 500L;

    private TxExecutor() {
    }

    static <R> R call(TxStarter starter, TxCallback<R> body) {
        TxOptions options = starter.options();
        int maxAttempts = Math.max(1, options.retryAttempts());

        int attempt = 1;
        while (true) {
            Tx tx = starter.begin();
            boolean succeeded = false;
            try {
                R result = body.apply(tx);
                if (result instanceof java.util.stream.Stream<?>
                        && tx instanceof TxImpl impl
                        && impl.hasTransactionalStreams()) {
                    throw new TxException("A transactional Stream must be consumed inside the transaction; "
                            + "returning it from txCall is not allowed.");
                }
                if (!(tx instanceof TxImpl impl) || !impl.isCompleted()) {
                    tx.commit();
                }
                succeeded = true;
                return result;
            } catch (Throwable primary) {
                boolean cleanupSucceeded = rollbackAndCloseQuietly(tx, primary);
                boolean confirmedRolledBack = tx instanceof TxImpl impl && impl.isPhysicalRollbackConfirmed();
                if (attempt < maxAttempts && cleanupSucceeded && confirmedRolledBack && isRetryable(primary)) {
                    if (!sleepBeforeRetry(attempt, primary)) {
                        throw compose(primary);
                    }
                    attempt++;
                    continue;
                }
                throw compose(primary);
            } finally {
                // 成功路径也必须归还连接并还原属性（RestorePoint 在此生效）。
                // close() 幂等，异常路径已关闭也无副作用。
                if (succeeded) {
                    try {
                        tx.close();
                    } catch (Throwable closeFailure) {
                        // 已提交成功，关闭失败不掩盖业务结果，仅记录
                        loggerIfAvailable(closeFailure);
                    }
                }
            }
        }
    }

    private static boolean sleepBeforeRetry(int attempt, Throwable primary) {
        long expDelay = Math.min(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 6)));
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(BASE_RETRY_DELAY_MS);
        try {
            Thread.sleep(expDelay + jitter);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            primary.addSuppressed(ie);
            return false;
        }
    }

    private static void loggerIfAvailable(Throwable t) {
        // 已提交成功，关闭失败只记录，避免静默吞掉
        org.slf4j.LoggerFactory.getLogger(TxExecutor.class)
                .warn("Failed to close transaction resources", t);
    }


    /**
     * 是否可重试：仅明确的死锁 / 串行化冲突（SQLState 40001、40P01，以及 MySQL 死锁/锁等待错误码 1213/1205），
     * 排除结果未知的 40003（statement_completion_unknown）以避免非幂等写入重复执行。
     *
     * <p>其他异常（约束冲突、语法错误、业务异常）重试无意义，必须立即失败。
     */
    static boolean isRetryable(Throwable t) {
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth++ < 20) {
            if (cursor instanceof SQLException se) {
                String state = se.getSQLState();
                if ("40001".equals(state) || "40P01".equalsIgnoreCase(state)) {
                    return true;
                }
                int vendorCode = se.getErrorCode();
                if (vendorCode == 1213 || vendorCode == 1205) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** 把主异常原样抛出；清理异常已由调用方挂到 suppressed。 */
    private static RuntimeException compose(Throwable primary) {
        if (primary instanceof RuntimeException re) {
            return re;
        }
        if (primary instanceof Error err) {
            throw err;
        }
        return new TxException("Transaction failed.", primary);
    }

    private static boolean rollbackAndCloseQuietly(Tx tx, Throwable primary) {
        boolean succeeded = true;
        try {
            tx.rollback();
        } catch (Throwable rollbackFailure) {
            succeeded = false;
            primary.addSuppressed(rollbackFailure);
        }
        try {
            tx.close();
        } catch (Throwable closeFailure) {
            succeeded = false;
            primary.addSuppressed(closeFailure);
        }
        return succeeded;
    }
}
