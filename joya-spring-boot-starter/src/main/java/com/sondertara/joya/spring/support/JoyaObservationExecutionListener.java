package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Micrometer {@link ObservationRegistry} 的 SQL 执行观测监听器：
 * <ul>
 *   <li>无 {@code ThreadLocal} 作用域残留，以 {@link SqlExecutionContext} 对象弱引用身份键（{@link WeakIdentityKey}）精确配对
 *       {@code beforeExecute} 与 {@code afterExecute} / {@code onStreamClosed} / {@code onError}，并发同名 SQL 零冲突；</li>
 *   <li>若调用方遗漏关闭流式 {@code Stream}，待上下文随流一起被 GC 回收后通过 {@link ReferenceQueue} 自动回收并停止残留
 *       {@link Observation}，彻底杜绝活跃映射表无界增长；</li>
 *   <li>流式查询（{@link SqlExecutionContext#streaming()}）延后至 {@link #onStreamClosed(SqlExecutionContext)} 停止
 *       {@link Observation}，完整覆盖游标消费耗时；</li>
 *   <li>默认不记录物理 SQL 文本（高基数 {@code db.statement} 需通过 {@code joya.observation.include-sql=true} 显式开启）。</li>
 * </ul>
 */
public final class JoyaObservationExecutionListener implements SqlExecutionListener {

    public static final String OBSERVATION_NAME = "joya.sql";

    private final ObservationRegistry registry;
    private final boolean includeSql;
    private final ConcurrentHashMap<WeakIdentityKey, Observation> active = new ConcurrentHashMap<>();
    private final ReferenceQueue<SqlExecutionContext> staleQueue = new ReferenceQueue<>();

    public JoyaObservationExecutionListener(ObservationRegistry registry, boolean includeSql) {
        this.registry = Objects.requireNonNull(registry, "observationRegistry must not be null");
        this.includeSql = includeSql;
    }

    @Override
    public void beforeExecute(SqlExecutionContext ctx) {
        drainStaleEntries();
        if (registry.isNoop()) {
            return;
        }
        StatementMetadata meta = ctx.metadata();
        Operation op = meta != null ? meta.operation() : null;
        String opName = op != null ? op.name().toLowerCase(Locale.ROOT) : "unknown";
        DbType dbType = meta != null ? meta.dbType() : null;
        String dbSystem = dbType != null ? dbType.name().toLowerCase(Locale.ROOT) : "unknown";
        String clientName = ctx.clientName() != null ? ctx.clientName() : "default";
        String primaryTable = extractPrimaryTable(meta);

        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(primaryTable != null ? "joya " + opName + " " + primaryTable : "joya " + opName)
                .lowCardinalityKeyValue("db.system", dbSystem)
                .lowCardinalityKeyValue("db.operation", opName)
                .lowCardinalityKeyValue("joya.client", clientName)
                .lowCardinalityKeyValue("joya.batch", String.valueOf(ctx.batch()))
                .lowCardinalityKeyValue("joya.streaming", String.valueOf(ctx.streaming()));

        if (primaryTable != null) {
            observation.highCardinalityKeyValue("db.sql.table", primaryTable);
        }
        if (ctx.batch() && ctx.batchRows() >= 0) {
            observation.highCardinalityKeyValue("joya.batch.rows", String.valueOf(ctx.batchRows()));
        }
        if (includeSql) {
            observation.highCardinalityKeyValue("db.statement", ctx.statement().sql());
        }
        observation.start();
        active.put(new WeakIdentityKey(ctx, staleQueue), observation);
    }

    @Override
    public void afterExecute(SqlExecutionContext ctx, SqlExecutionResult result) {
        drainStaleEntries();
        if (ctx.streaming()) {
            Observation obs = active.get(new WeakIdentityKey(ctx, null));
            if (obs != null) {
                obs.event(Observation.Event.of("stream.opened"));
            }
            return;
        }
        Observation obs = active.remove(new WeakIdentityKey(ctx, null));
        if (obs != null) {
            obs.stop();
        }
    }

    @Override
    public void onError(SqlExecutionContext ctx, Throwable error, long elapsedNanos) {
        drainStaleEntries();
        Observation obs = active.remove(new WeakIdentityKey(ctx, null));
        if (obs != null) {
            obs.error(error);
            obs.stop();
        }
    }

    @Override
    public void onStreamClosed(SqlExecutionContext ctx) {
        drainStaleEntries();
        Observation obs = active.remove(new WeakIdentityKey(ctx, null));
        if (obs != null) {
            obs.stop();
        }
    }

    /** 清理已被 GC 回收但未显式调用 {@code close()} 的遗弃流对应观测记录，并触发 {@code stop()}。 */
    private void drainStaleEntries() {
        WeakIdentityKey stale;
        while ((stale = (WeakIdentityKey) staleQueue.poll()) != null) {
            Observation obs = active.remove(stale);
            if (obs != null) {
                obs.stop();
            }
        }
    }

    /** 包内可见仅用于测试验证活跃表未发生泄漏。 */
    public int activeObservationCount() {
        drainStaleEntries();
        return active.size();
    }

    private static @Nullable String extractPrimaryTable(@Nullable StatementMetadata meta) {
        if (meta == null || meta.tables().isEmpty()) {
            return null;
        }
        TableRef first = meta.tables().iterator().next();
        String qualified = first.qualifiedName();
        return (qualified != null && !qualified.isBlank()) ? qualified : first.name();
    }

    /**
     * 按对象引用身份（而非 record 值相等）索引活跃 Observation，并以 {@link WeakReference} 兜底防漏关流泄漏：
     * 正常存活的流由 {@code ExecutionObserver} 的 {@code onClose} 闭包强引用 {@code ctx}，绝不会被误回收。
     */
    private static final class WeakIdentityKey extends WeakReference<SqlExecutionContext> {
        private final int hash;

        private WeakIdentityKey(SqlExecutionContext target, @Nullable ReferenceQueue<SqlExecutionContext> queue) {
            super(target, queue);
            this.hash = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof WeakIdentityKey other)) {
                return false;
            }
            SqlExecutionContext a = get();
            SqlExecutionContext b = other.get();
            return a != null && a == b;
        }
    }
}
