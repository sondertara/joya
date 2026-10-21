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
                tx.commit();
                succeeded = true;
                return result;
            } catch (Throwable primary) {
                if (attempt < maxAttempts && isRetryable(primary)) {
                    rollbackAndCloseQuietly(tx, primary);
                    attempt++;
                    continue;
                }
                throw rollbackAndCompose(tx, primary);
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

    private static void loggerIfAvailable(Throwable t) {
        // 已提交成功，关闭失败只记录，避免静默吞掉
        org.slf4j.LoggerFactory.getLogger(TxExecutor.class)
                .warn("Failed to close transaction resources", t);
    }


    /**
     * 是否可重试：仅死锁 / 序列化失败（SQLState 以 40 开头，如 40001、40P01）。
     *
     * <p>其他异常（约束冲突、语法错误、业务异常）重试无意义，必须立即失败。
     */
    static boolean isRetryable(Throwable t) {
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth++ < 20) {
            if (cursor instanceof SQLException se) {
                String state = se.getSQLState();
                if (state != null && state.startsWith("40")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /** 回滚 + 关闭，并把主异常原样抛出（清理失败一律挂到 suppressed）。 */
    private static RuntimeException rollbackAndCompose(Tx tx, Throwable primary) {
        rollbackAndCloseQuietly(tx, primary);

        if (primary instanceof RuntimeException re) {
            return re;
        }
        if (primary instanceof Error err) {
            throw err;
        }
        return new TxException("Transaction failed.", primary);
    }

    private static void rollbackAndCloseQuietly(Tx tx, Throwable primary) {
        try {
            tx.rollback();
        } catch (Throwable rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
        try {
            tx.close();
        } catch (Throwable closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}
