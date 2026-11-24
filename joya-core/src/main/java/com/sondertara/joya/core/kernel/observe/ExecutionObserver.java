package com.sondertara.joya.core.kernel.observe;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.support.Ordered;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 执行观测编排器：把 {@link SqlExecutionListener} 的 before / after / onError / onStreamClosed 回调
 * 包装在一次执行外围。监听器按 {@code order()} 升序（同值按类名字典序）在首次执行时惰性排序一次
 * （扩展点可能是容器启动期的循环依赖代理，构造期读取 {@code order()} 不可靠），
 * 监听器自身抛出的异常只记录日志，不影响执行；监听器为空时零额外开销。
 */
public final class ExecutionObserver {

    private static final Logger log = LoggerFactory.getLogger(ExecutionObserver.class);

    private final List<SqlExecutionListener> listeners;
    private final @Nullable String clientName;
    private volatile List<SqlExecutionListener> orderedListeners;

    public ExecutionObserver(List<SqlExecutionListener> listeners) {
        this(listeners, null);
    }

    public ExecutionObserver(List<SqlExecutionListener> listeners, @Nullable String clientName) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clientName = (clientName == null || clientName.isBlank()) ? null : clientName.trim();
    }

    private List<SqlExecutionListener> orderedListeners() {
        List<SqlExecutionListener> local = orderedListeners;
        if (local == null) {
            synchronized (this) {
                local = orderedListeners;
                if (local == null) {
                    local = Ordered.sort(listeners);
                    orderedListeners = local;
                }
            }
        }
        return local;
    }

    /** 已注册的监听器列表（供 {@code bind} 派生视图复用）。 */
    public List<SqlExecutionListener> listeners() {
        return listeners;
    }

    /** 是否注册了任何监听器（供调用方在无监听时跳过观测视图对象分配）。 */
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    @FunctionalInterface
    public interface StreamCloseRegistrar {
        <T> Stream<T> register(Stream<T> stream, Runnable cleanup);
    }

    /**
     * 流式执行观测包装：before → 执行 → after/onError，流关闭时触发 onStreamClosed。监听器为空时零额外开销。
     */
    public <T> Stream<T> observeStream(SqlStatement statement, Supplier<Stream<T>> action) {
        return observeStream(statement, null, action, Stream::onClose);
    }

    public <T> Stream<T> observeStream(SqlStatement statement,
                                       SqlExecutionContext.@Nullable PaginationView page,
                                       Supplier<Stream<T>> action) {
        return observeStream(statement, page, action, Stream::onClose);
    }

    public <T> Stream<T> observeStream(SqlStatement statement,
                                       SqlExecutionContext.@Nullable PaginationView page,
                                       Supplier<Stream<T>> action,
                                       StreamCloseRegistrar closeRegistrar) {
        if (listeners.isEmpty()) {
            return action.get();
        }
        List<SqlExecutionListener> active = orderedListeners();
        SqlExecutionContext ctx = new SqlExecutionContext(statement, false, -1, clientName, page, true);
        for (SqlExecutionListener listener : active) {
            runListenerQuietly(() -> listener.beforeExecute(ctx), "beforeExecute");
        }
        long start = System.nanoTime();
        final Stream<T> stream;
        try {
            stream = action.get();
        } catch (Throwable error) {
            long elapsedNanos = System.nanoTime() - start;
            for (SqlExecutionListener listener : active) {
                runListenerQuietly(() -> listener.onError(ctx, error, elapsedNanos), "onError");
            }
            throw error;
        }
        try {
            SqlExecutionResult summary = SqlExecutionResult.rows(-1L)
                    .withElapsedNanos(System.nanoTime() - start);
            for (SqlExecutionListener listener : active) {
                runListenerQuietly(() -> listener.afterExecute(ctx, summary), "afterExecute");
            }
            java.util.concurrent.atomic.AtomicBoolean closeNotified = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable notifyClosed = () -> {
                if (closeNotified.compareAndSet(false, true)) {
                    for (SqlExecutionListener listener : active) {
                        runListenerQuietly(() -> listener.onStreamClosed(ctx), "onStreamClosed");
                    }
                }
            };
            return closeRegistrar.register(stream, notifyClosed);
        } catch (Throwable postError) {
            try {
                stream.close();
            } catch (Throwable closeErr) {
                postError.addSuppressed(closeErr);
            }
            throw postError;
        }
    }

    public <T> T observe(SqlStatement statement,
                         Supplier<T> action,
                         Function<T, SqlExecutionResult> resultMapper) {
        return observe(statement, false, -1, null, action, resultMapper);
    }

    public <T> T observe(SqlStatement statement,
                         SqlExecutionContext.@Nullable PaginationView page,
                         Supplier<T> action,
                         Function<T, SqlExecutionResult> resultMapper) {
        return observe(statement, false, -1, page, action, resultMapper);
    }

    public <T> T observeBatch(SqlStatement probeStatement,
                              int batchRows,
                              Supplier<T> action,
                              Function<T, SqlExecutionResult> resultMapper) {
        return observe(probeStatement, true, batchRows, null, action, resultMapper);
    }

    private <T> T observe(SqlStatement statement,
                          boolean batch,
                          int batchRows,
                          SqlExecutionContext.@Nullable PaginationView page,
                          Supplier<T> action,
                          Function<T, SqlExecutionResult> resultMapper) {
        if (listeners.isEmpty()) {
            return action.get();
        }
        List<SqlExecutionListener> active = orderedListeners();
        SqlExecutionContext ctx = new SqlExecutionContext(statement, batch, batchRows, clientName, page);
        for (SqlExecutionListener listener : active) {
            runListenerQuietly(() -> listener.beforeExecute(ctx), "beforeExecute");
        }
        long start = System.nanoTime();
        final T result;
        try {
            result = action.get();
        } catch (Throwable error) {
            long elapsedNanos = System.nanoTime() - start;
            for (SqlExecutionListener listener : active) {
                runListenerQuietly(() -> listener.onError(ctx, error, elapsedNanos), "onError");
            }
            throw error;
        }
        long elapsedNanos = System.nanoTime() - start;
        SqlExecutionResult summary = resultMapper.apply(result).withElapsedNanos(elapsedNanos);
        for (SqlExecutionListener listener : active) {
            runListenerQuietly(() -> listener.afterExecute(ctx, summary), "afterExecute");
        }
        return result;
    }

    private static void runListenerQuietly(Runnable action, String phase) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("SqlExecutionListener {} failed; execution is not affected", phase, e);
        }
    }
}
