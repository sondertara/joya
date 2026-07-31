/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.support;

import com.sondertara.joya.core.jdbc.transaction.Isolation;
import com.sondertara.joya.core.jdbc.transaction.SavepointManager;
import com.sondertara.joya.exceptions.DbException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * 线程级连接上下文，合并了 {@code ConnectionHolder} + {@code SavepointManager} + 事务状态。
 * <p>
 * 职责：管理一个 JDBC 连接的引用计数、事务标志、隔离级别恢复以及保存点。
 * <p>
 * 该对象通常存活在 {@link TransactionSynchronization} 的 ThreadLocal 中。
 *
 * @author huangxiaohu
 */
public final class ConnectionContext implements SavepointManager {

    private static final int VALIDATION_TIMEOUT = 10;
    private static final String SAVEPOINT_PREFIX = "SAVEPOINT_";

    private final DataSource dataSource;
    private Connection connection;
    private int referenceCount;
    private boolean managedTransaction;   // 是否经过 beginTransaction 开启的事务
    private Isolation originalIsolation;
    private int savepointCounter;

    // ============ lifecycle ============

    public ConnectionContext(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ============ connection ============

    public Connection getConnection() {
        if (connection == null) {
            try {
                connection = dataSource.getConnection();
            } catch (SQLException e) {
                throw new DbException(e);
            }
            return connection;
        }
        try {
            if (connection.isClosed()) {
                connection = dataSource.getConnection();
            } else if (!connection.isValid(VALIDATION_TIMEOUT)) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
                connection = dataSource.getConnection();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return connection;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    // ============ reference counting ============

    /**
     * 增加引用计数，确保连接已初始化。
     */
    public void retain() {
        getConnection(); // eager init
        referenceCount++;
    }

    /**
     * 减少引用计数。计数归零且不在事务中时，物理关闭连接。
     */
    public void release() throws SQLException {
        if (referenceCount > 0) {
            referenceCount--;
        }
        if (referenceCount <= 0 && !managedTransaction && connection != null) {
            connection.close();
            connection = null;
        }
    }

    public boolean isOpen() {
        return referenceCount > 0;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    // ============ transaction ============

    /**
     * 判断当前连接是否开启了事务（autoCommit = false）。
     */
    public boolean hasTransaction() {
        if (connection == null) return false;
        try {
            return !connection.getAutoCommit();
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /**
     * 将连接设为手动提交模式（autoCommit = false）。
     */
    public void beginTransaction() throws SQLException {
        getConnection();
        if (connection.getAutoCommit()) {
            connection.setAutoCommit(false);
            managedTransaction = true;
        }
    }

    /**
     * 恢复为自动提交模式。
     */
    public void endTransaction() throws SQLException {
        if (connection != null && !connection.getAutoCommit()) {
            connection.setAutoCommit(true);
        }
        managedTransaction = false;
    }

    /**
     * 记录了原始隔离级别，事务结束后用于恢复。
     */
    public void setOriginalIsolation(Isolation isolation) {
        this.originalIsolation = isolation;
    }

    public Isolation getOriginalIsolation() {
        return originalIsolation;
    }

    // ============ SavepointManager ============

    @Override
    public boolean supportSavepoint() throws SQLException {
        return getConnection().getMetaData().supportsSavepoints();
    }

    @Override
    public Savepoint createSavepoint() throws SQLException {
        savepointCounter++;
        return getConnection().setSavepoint(SAVEPOINT_PREFIX + savepointCounter);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        getConnection().releaseSavepoint(savepoint);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        getConnection().rollback(savepoint);
    }
}
