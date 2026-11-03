package com.sondertara.joya.core.kernel.executor;

/**
 * {@link Executor} 端口的默认适配器工厂入口。
 *
 * <p>独立于 {@link Executor} 端口接口，避免端口反向引用具体 JDBC 适配器 {@link JdbcExecutor}，
 * 同时保证 {@code JdbcExecutor} 仅在 {@code kernel.executor} 包内被引用。
 */
public final class Executors {

    /** 默认的 JDBC 执行器工厂。 */
    public static final Executor.Factory JDBC = JdbcExecutor::new;

    private Executors() {
    }
}
