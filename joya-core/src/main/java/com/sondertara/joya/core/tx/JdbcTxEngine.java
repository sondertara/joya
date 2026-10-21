package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.tx.TxEngine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

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
        try {
            Connection raw = rootTemplate.dataSource().getConnection();
            txConn = new TxConnection(raw);
            txConn.begin(options);
            return new TxImpl(rootTemplate, txConn);
        } catch (SQLException e) {
            if (txConn != null) {
                try {
                    txConn.close();
                } catch (SQLException closeEx) {
                    e.addSuppressed(closeEx);
                }
            }
            throw new TxException("Failed to begin transaction.", e);
        }
    }
}
