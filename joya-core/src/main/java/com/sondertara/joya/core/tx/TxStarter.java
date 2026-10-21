package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.tx.TxEngine;

import java.time.Duration;
import java.util.Objects;

/**
 * 事务启动器 —— 流式配置 + 终端操作。每个配置方法返回新实例（不可变，可安全复用）。
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

    private final JoyaTemplate template;
    private final TxEngine txEngine;
    private final TxOptions options;

    public TxStarter(JoyaTemplate template) {
        this(template, JdbcTxEngine.INSTANCE, TxOptions.DEFAULT);
    }

    public TxStarter(JoyaTemplate template, TxOptions options) {
        this(template, JdbcTxEngine.INSTANCE, options);
    }

    public TxStarter(JoyaTemplate template, TxEngine txEngine) {
        this(template, txEngine, TxOptions.DEFAULT);
    }

    public TxStarter(JoyaTemplate template, TxEngine txEngine, TxOptions options) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.txEngine = Objects.requireNonNull(txEngine, "txEngine must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    // ===== 流式配置（每个方法返回新实例） =====

    public TxStarter isolation(Isolation isolation) {
        return new TxStarter(template, txEngine, options.withIsolation(isolation));
    }

    public TxStarter serializable() {
        return isolation(Isolation.SERIALIZABLE);
    }

    public TxStarter readOnly() {
        return new TxStarter(template, txEngine, options.withReadOnly(true));
    }

    /** 事务超时：超时后后续语句抛 {@link TxTimeoutException}，并作为语句级 queryTimeout 下沉。 */
    public TxStarter timeoutSeconds(int seconds) {
        return timeout(Duration.ofSeconds(seconds));
    }

    public TxStarter timeout(Duration timeout) {
        return new TxStarter(template, txEngine, options.withTimeout(timeout));
    }

    /** 死锁 / 序列化失败（SQLState 40xxx）时整体重试，最多尝试 {@code maxAttempts} 次。仅对 run/call 生效。 */
    public TxStarter retryOnDeadlock(int maxAttempts) {
        return new TxStarter(template, txEngine, options.withRetryAttempts(maxAttempts));
    }

    // ===== 终端操作 =====

    /** 开启事务（RAII）：必须 {@code try-with-resources}，未 {@link Tx#commit()} 的部分在 close 时回滚。 */
    public Tx begin() {
        return txEngine.begin(template, options);
    }

    /** 在事务中执行：正常结束提交，异常回滚并原样抛出业务异常。 */
    public void run(TxConsumer body) {
        TxExecutor.call(this, tx -> {
            body.accept(tx);
            return null;
        });
    }

    /** 在事务中执行并返回结果。 */
    public <R> R call(TxCallback<R> body) {
        return TxExecutor.call(this, body);
    }

    public TxOptions options() {
        return options;
    }

    public TxEngine txEngine() {
        return txEngine;
    }
}
