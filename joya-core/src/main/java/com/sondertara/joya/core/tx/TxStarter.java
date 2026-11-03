package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * 事务启动器 —— 流式不可变配置 + 终端操作与死锁退避重试编排。
 *
 * <pre>{@code
 * joya.tx(tx -> { ... });                                           // 快捷无返回值
 * Long id = joya.txCall(tx -> tx.of(User.class).insert(u).generatedKey(Long.class));
 * joya.tx().readOnly().timeoutSeconds(30).run(tx -> { ... });
 * try (Tx tx = joya.tx().serializable().begin()) { ...; tx.commit(); }   // 跨方法 / RAII
 *
 * static final TxStarter REPORT = joya.tx().readOnly().timeoutSeconds(30);  // 可复用
 * }</pre>
 */
public final class TxStarter {

    private static final Logger log = LoggerFactory.getLogger(TxStarter.class);
    private static final long BASE_RETRY_DELAY_MS = 10L;
    private static final long MAX_RETRY_DELAY_MS = 500L;

    private final ExecutionKernel template;
    private final TxEngine txEngine;
    private final TxOptions options;
    private final EntityLifecycle entityLifecycle;

    public TxStarter(ExecutionKernel template) {
        this(template, TxEngine.JDBC, TxOptions.DEFAULT, EntityLifecycle.EMPTY);
    }

    public TxStarter(ExecutionKernel template, TxOptions options) {
        this(template, TxEngine.JDBC, options, EntityLifecycle.EMPTY);
    }

    public TxStarter(ExecutionKernel template, TxEngine txEngine) {
        this(template, txEngine, TxOptions.DEFAULT, EntityLifecycle.EMPTY);
    }

    public TxStarter(ExecutionKernel template, TxEngine txEngine, TxOptions options) {
        this(template, txEngine, options, EntityLifecycle.EMPTY);
    }

    public TxStarter(ExecutionKernel template, TxEngine txEngine, TxOptions options, EntityLifecycle entityLifecycle) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.txEngine = Objects.requireNonNull(txEngine, "txEngine must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.entityLifecycle = entityLifecycle == null ? EntityLifecycle.EMPTY : entityLifecycle;
    }

    // ===== 流式配置（每个方法返回新实例） =====

    public TxStarter isolation(Isolation isolation) {
        return new TxStarter(template, txEngine, options.withIsolation(isolation), entityLifecycle);
    }

    public TxStarter serializable() {
        return isolation(Isolation.SERIALIZABLE);
    }

    public TxStarter readOnly() {
        return new TxStarter(template, txEngine, options.withReadOnly(true), entityLifecycle);
    }

    /** 事务超时：超时后后续语句抛 {@link TxTimeoutException}，并作为语句级 queryTimeout 下沉。 */
    public TxStarter timeoutSeconds(int seconds) {
        return timeout(Duration.ofSeconds(seconds));
    }

    public TxStarter timeout(Duration timeout) {
        return new TxStarter(template, txEngine, options.withTimeout(timeout), entityLifecycle);
    }

    /**
     * 死锁 / 序列化失败（SQLState 40001 / 40P01，MySQL 1213 / 1205）时整体重试，最多尝试 {@code maxAttempts} 次。
     * 仅对 {@link #run} / {@link #call} 生效；每次重试开启全新的 {@link Tx} 实例重跑闭包，
     * 期间注册的 {@link Tx#afterCompletion} 仅在最终尝试完成时触发一次。
     */
    public TxStarter retryOnDeadlock(int maxAttempts) {
        return new TxStarter(template, txEngine, options.withRetryAttempts(maxAttempts), entityLifecycle);
    }

    // ===== 终端操作 =====

    /** 开启事务（RAII）：必须 {@code try-with-resources}，未 {@link Tx#commit()} 的部分在 close 时回滚。 */
    public Tx begin() {
        return open();
    }

    /** 由引擎开启物理会话，并统一包装为唯一的 {@link Tx} 实现 {@link TxImpl}。 */
    private TxImpl open() {
        TxSession session = txEngine.open(template, options);
        try {
            return new TxImpl(template, session, entityLifecycle);
        } catch (Throwable wrapFailure) {
            try {
                session.close();
            } catch (Throwable closeFailure) {
                wrapFailure.addSuppressed(closeFailure);
            }
            throw wrapFailure;
        }
    }

    /** 在事务中执行：正常结束提交，异常回滚并原样抛出业务异常。 */
    public void run(TxConsumer body) {
        Objects.requireNonNull(body, "body must not be null");
        call(tx -> {
            body.accept(tx);
            return null;
        });
    }

    /**
     * 在事务中执行并返回结果（遵循不变量 I8：业务异常始终为主异常，回滚或关闭异常作为 {@code suppressed} 附加）。
     */
    public <R> R call(TxCallback<R> body) {
        Objects.requireNonNull(body, "body must not be null");
        int maxAttempts = Math.max(1, options.retryAttempts());
        int attempt = 1;
        while (true) {
            TxImpl tx = open();
            if (maxAttempts > 1) {
                tx.deferCompletionCallbacks(true);
            }
            boolean succeeded = false;
            try {
                R result = body.apply(tx);
                if (result instanceof Stream<?> && tx.hasTransactionalStreams()) {
                    throw new TxException("A transactional Stream must be consumed inside the transaction; "
                            + "returning it from txCall is not allowed.");
                }
                if (!tx.isCompleted()) {
                    tx.commit();
                }
                succeeded = true;
                return result;
            } catch (Throwable primary) {
                boolean cleanupSucceeded = rollbackAndCloseQuietly(tx, primary);
                boolean confirmedRolledBack = tx.isPhysicalRollbackConfirmed();
                if (attempt < maxAttempts && cleanupSucceeded && confirmedRolledBack && isRetryable(primary)) {
                    if (!sleepBeforeRetry(attempt, primary)) {
                        tx.fireCompletionCallbacks();
                        throw compose(primary);
                    }
                    attempt++;
                    continue;
                }
                tx.fireCompletionCallbacks();
                throw compose(primary);
            } finally {
                if (succeeded) {
                    try {
                        tx.close();
                    } catch (Throwable closeFailure) {
                        log.warn("Failed to close transaction resources", closeFailure);
                    }
                    tx.fireCompletionCallbacks();
                }
            }
        }
    }

    public TxOptions options() {
        return options;
    }

    /**
     * 是否可重试：仅明确的死锁 / 串行化冲突（SQLState 40001、40P01，以及 MySQL 死锁/锁等待错误码 1213/1205），
     * 排除结果未知的 40003（statement_completion_unknown）以避免非幂等写入重复执行。
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

    private static boolean sleepBeforeRetry(int attempt, Throwable primary) {
        long expDelay = Math.min(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS * (1L << Math.min(attempt - 1, 6)));
        long jitter = ThreadLocalRandom.current().nextLong(BASE_RETRY_DELAY_MS);
        try {
            Thread.sleep(expDelay + jitter);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            primary.addSuppressed(ie);
            return false;
        }
    }

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
