package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.JoyaTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 纯 JDBC 事务引擎默认实现：直接从 {@link javax.sql.DataSource} 借出一条独占连接并封装为 {@link TxConnection}。
 */
public final class JdbcTxEngine implements TxEngine {

    public static final JdbcTxEngine INSTANCE = new JdbcTxEngine();

    @Override
    public Tx begin(JoyaTemplate rootTemplate, TxOptions options) {
        Objects.requireNonNull(rootTemplate, "rootTemplate must not be null");
        Objects.requireNonNull(options, "options must not be null");
        TxConnection txConn = null;
        Connection raw = null;
        boolean useCustomProvider = !(rootTemplate.connections()
                instanceof com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider);
        try {
            raw = useCustomProvider
                    ? rootTemplate.connections().acquire()
                    : rootTemplate.dataSource().getConnection();
            txConn = new TxConnection(raw, useCustomProvider ? rootTemplate.connections() : null,
                    defaultQueryTimeout(rootTemplate));
            txConn.begin(options);
            return new TxImpl(rootTemplate, txConn);
        } catch (Throwable beginFailure) {
            try {
                if (txConn != null) {
                    txConn.close();
                } else if (raw != null) {
                    if (useCustomProvider) {
                        rootTemplate.connections().release(raw);
                    } else {
                        raw.close();
                    }
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
    }

    private static OptionalInt defaultQueryTimeout(JoyaTemplate rootTemplate) {
        OptionalInt providerTimeout = rootTemplate.connections().queryTimeoutSeconds();
        if (providerTimeout != null && providerTimeout.isPresent()) {
            return providerTimeout;
        }
        int configuredTimeout = rootTemplate.executionOptions().queryTimeoutSeconds();
        return configuredTimeout > 0 ? OptionalInt.of(configuredTimeout) : OptionalInt.empty();
    }
}
