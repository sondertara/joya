package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 事务引擎扩展点（SPI）：负责在指定 {@link ExecutionKernel} 与 {@link TxOptions} 下开启一个底层事务会话 {@link TxSession}。
 *
 * <p>引擎只产出<b>物理事务会话</b>；{@link Tx} 门面（保存点、回调、生命周期编排、实体监听器）
 * 由 {@link TxStarter} 统一包装为 {@link TxImpl}，因此重试、流保护、回调延迟等语义对任何引擎都一致生效。
 *
 * <ul>
 *   <li>纯 JDBC 环境：默认使用 {@link #JDBC}，经 {@link ConnectionProvider} 借出一条独占连接并封装为 {@link TxConnection}。</li>
 *   <li>Spring 环境：由 {@code SpringTxEngine} 委托给 {@code PlatformTransactionManager}，实现与 {@code @Transactional}
 *       及主 ORM 框架共享同一条连接与事务生命周期。</li>
 * </ul>
 */
@FunctionalInterface
public interface TxEngine {

    /**
     * 纯 JDBC 事务引擎默认实现：统一经 {@link ExecutionKernel#connections()} 借还连接，
     * 不再直接读取 {@code DataSource}（连接来源只有 {@link ConnectionProvider} 一个真相源）。
     */
    TxEngine JDBC = (rootKernel, options) -> {
        Objects.requireNonNull(rootKernel, "rootKernel must not be null");
        Objects.requireNonNull(options, "options must not be null");
        ConnectionProvider connections = rootKernel.connections();
        TxConnection txConn = null;
        Connection raw = null;
        try {
            raw = connections.acquire();
            txConn = new TxConnection(raw, connections, TxSessionSupport.resolveDefaultQueryTimeout(rootKernel));
            txConn.begin(options);
            return txConn;
        } catch (Throwable beginFailure) {
            try {
                if (txConn != null) {
                    txConn.close();
                } else if (raw != null) {
                    connections.release(raw);
                }
            } catch (Throwable cleanupFailure) {
                beginFailure.addSuppressed(cleanupFailure);
            }
            if (beginFailure instanceof SQLException e) {
                throw new TxException("Failed to begin transaction.", e);
            }
            if (beginFailure instanceof RuntimeException e) {
                throw e;
            }
            if (beginFailure instanceof Error e) {
                throw e;
            }
            throw new TxException("Failed to begin transaction.", beginFailure);
        }
    };

    /**
     * 开启一个底层事务会话。
     *
     * @param rootKernel 客户端根执行模板
     * @param options      事务配置选项
     * @return 已开启的事务会话；由调用方（{@link TxStarter}）负责包装为 {@link Tx} 并最终关闭
     */
    TxSession open(ExecutionKernel rootKernel, TxOptions options);

    /**
     * 校验当前事务引擎与客户端 {@link DataSource} 及 {@link ConnectionProvider} 的配置一致性。
     *
     * <p>外部受管事务引擎（如 {@code SpringTxEngine}）可在此校验 {@code connectionProvider}
     * 必须为同源的受管连接提供者，且底层 {@code DataSource} 与客户端一致；不满足时抛出 {@link IllegalArgumentException}。
     */
    default void validateConfiguration(DataSource dataSource, ConnectionProvider connectionProvider) {
    }
}
