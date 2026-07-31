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
package com.sondertara.joya.core.jdbc.transaction;

import com.sondertara.joya.core.jdbc.support.ConnectionContext;

import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * 事务管理器内部使用的可变事务状态实现。
 * <p>
 * 提供 {@link TransactionStatus} 的对外只读接口与对内写入入口
 * （{@link #markRollbackOnly()}、{@link #markReadOnly()}、保存点操作等），
 * 让状态变更只能由 {@link TransactionManager} 路径触发。
 *
 * @author huangxiaohu
 */
public class AbstractTransactionStatus implements TransactionStatus {

    private final Propagation propagation;
    private final Isolation isolation;

    private ConnectionContext connectionContext;
    /** 挂起外层事务前的连接上下文，恢复时使用 */
    private ConnectionContext suspendedContext;

    private boolean newConnection;
    private boolean rollbackOnly;
    private boolean readOnly;
    private boolean completed;
    private boolean suspend;

    private Savepoint savepoint;

    public AbstractTransactionStatus(Propagation propagation, Isolation isolation) {
        this.propagation = propagation;
        this.isolation = isolation;
    }

    @Override
    public Propagation getPropagation() {
        return propagation;
    }

    @Override
    public Isolation getIsolationLevel() {
        return isolation;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean isNewConnection() {
        return newConnection;
    }

    @Override
    public boolean isSuspend() {
        return suspend;
    }

    @Override
    public boolean hasSavepoint() {
        return savepoint != null;
    }

    // ============ internal mutation (package-internal use only) ============

    public ConnectionContext getConnectionContext() {
        return connectionContext;
    }

    public void setConnectionContext(ConnectionContext ctx) {
        this.connectionContext = ctx;
    }

    public ConnectionContext getSuspendedContext() {
        return suspendedContext;
    }

    public void setSuspendedContext(ConnectionContext ctx) {
        this.suspendedContext = ctx;
    }

    public void markNewConnection() {
        this.newConnection = true;
    }

    public void markSuspend() {
        this.suspend = true;
    }

    /**
     * 标记事务为仅回滚；递交时将走回滚路径。
     */
    public void markRollbackOnly() {
        if (completed) {
            throw new IllegalStateException("Transaction is already completed.");
        }
        this.rollbackOnly = true;
    }

    /**
     * 标记只读事务；递交时将走回滚路径。
     */
    public void markReadOnly() {
        if (completed) {
            throw new IllegalStateException("Transaction is already completed.");
        }
        this.readOnly = true;
    }

    public void setCompleted() {
        this.completed = true;
    }

    // ============ savepoint (delegates to ConnectionContext) ============

    public void markSavepoint() throws SQLException {
        ConnectionContext ctx = connectionContext;
        if (ctx == null) {
            throw new SQLException("ConnectionContext is null; cannot create savepoint.");
        }
        this.savepoint = ctx.createSavepoint();
    }

    public void releaseSavepoint() throws SQLException {
        if (savepoint == null) {
            return;
        }
        connectionContext.releaseSavepoint(savepoint);
        this.savepoint = null;
    }

    public void rollbackToSavepoint() throws SQLException {
        if (savepoint == null) {
            throw new SQLException("No savepoint to roll back to.");
        }
        connectionContext.rollback(savepoint);
        this.savepoint = null;
    }
}
