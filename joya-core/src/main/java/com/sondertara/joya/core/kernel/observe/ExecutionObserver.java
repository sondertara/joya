package com.sondertara.joya.core.kernel.observe;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 执行观测编排器：把 {@link SqlExecutionListener} 的 before / after / onError / onStreamClosed 回调
 * 包装在一次执行外围。监听器按 {@code order()} 升序（同值按类名字典序）在构造时排序一次，
 * 监听器自身抛出的异常只记录日志，不影响执行；监听器为空时零额外开销。
 */
public final class ExecutionObserver {

    private static final Logger log = LoggerFactory.getLogger(ExecutionObserver.class);

    private final List<SqlExecutionListener> listeners;

    public ExecutionObserver(List<SqlExecutionListener> listeners) {
        this.listeners = sort(listeners);
    }

    private static List<SqlExecutionListener> sort(List<SqlExecutionListener> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            return List.of();
        }
        List<SqlExecutionListener> sorted = new ArrayList<>(listeners);
        sorted.sort(Comparator.comparingInt(SqlExecutionListener::order)
                .thenComparing(l -> l.getClass().getName()));
        return List.copyOf(sorted);
    }

    /** 已排序的监听器（供 {@code bind} 派生视图复用）。 */
    public List<SqlExecutionListener> listeners() {
        return listeners;
    }

    /**
     * 执行观测包装：before → 执行 → after/onError。监听器为空时零额外开销。
     */
    public <T> Stream<T> observeStream(SqlStatement statement, Map<String, Object> attributes,
                                       Supplier<Stream<T>> action) {
        if (listeners.isEmpty()) {
            return action.get();
        }
        SqlExecutionContext ctx = new SqlExecutionContext(statement, false, attributes);
        for (SqlExecutionListener listener : listeners) {
            runListenerQuietly(() -> listener.beforeExecute(ctx), "beforeExecute");
        }
        long start = System.nanoTime();
        try {
            Stream<T> stream = action.get();
            SqlExecutionResult summary = SqlExecutionResult.rows(-1L)
                    .withElapsedNanos(System.nanoTime() - start);
            for (SqlExecutionListener listener : listeners) {
                runListenerQuietly(() -> listener.afterExecute(ctx, summary), "afterExecute");
            }
            return stream.onClose(() -> {
                for (SqlExecutionListener listener : listeners) {
                    runListenerQuietly(() -> listener.onStreamClosed(ctx), "onStreamClosed");
                }
            });
        } catch (Throwable error) {
            for (SqlExecutionListener listener : listeners) {
                runListenerQuietly(() -> listener.onError(ctx, error), "onError");
            }
            throw error;
        }
    }

    public <T> T observe(SqlStatement statement, boolean batch, Map<String, Object> attributes,
                          Supplier<T> action, Function<T, SqlExecutionResult> resultMapper) {
        if (listeners.isEmpty()) {
            return action.get();
        }
        SqlExecutionContext ctx = new SqlExecutionContext(statement, batch, attributes);
        for (SqlExecutionListener listener : listeners) {
            runListenerQuietly(() -> listener.beforeExecute(ctx), "beforeExecute");
        }
        long start = System.nanoTime();
        try {
            T result = action.get();
            SqlExecutionResult summary = resultMapper.apply(result)
                    .withElapsedNanos(System.nanoTime() - start);
            for (SqlExecutionListener listener : listeners) {
                runListenerQuietly(() -> listener.afterExecute(ctx, summary), "afterExecute");
            }
            return result;
        } catch (Throwable error) {
            for (SqlExecutionListener listener : listeners) {
                runListenerQuietly(() -> listener.onError(ctx, error), "onError");
            }
            throw error;
        }
    }

    private static void runListenerQuietly(Runnable action, String phase) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("SqlExecutionListener {} failed; execution is not affected", phase, e);
        }
    }
}
