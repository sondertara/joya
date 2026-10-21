package com.sondertara.joya.core.tx;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接属性还原点（不变量 I4 的实现）。
 *
 * <p>在事务最外层<b>捕获一次</b>连接的原始属性（autoCommit / 隔离级别 / readOnly），
 * 事务结束时原样还原。
 *
 * <p>两个关键点：
 * <ul>
 *   <li><b>只捕获一次</b>：嵌套事务不重复捕获，否则内层会把"被自己改过的值"
 *       当成原始值，导致无法还原（旧实现 P1-1 的成因）。</li>
 *   <li><b>逐项比对后再写</b>：还原时先比对当前值，相同则不发 SQL，
 *       避免每次事务结束多出 3 次无谓的 JDBC 往返。</li>
 * </ul>
 */
public final class RestorePoint {

    private final boolean autoCommit;
    private final int isolation;
    private final boolean readOnly;

    private RestorePoint(boolean autoCommit, int isolation, boolean readOnly) {
        this.autoCommit = autoCommit;
        this.isolation = isolation;
        this.readOnly = readOnly;
    }

    /** 捕获连接当前属性。 */
    public static RestorePoint capture(Connection connection) throws SQLException {
        return new RestorePoint(
                connection.getAutoCommit(),
                connection.getTransactionIsolation(),
                connection.isReadOnly());
    }

    /**
     * 还原连接属性到捕获时的值。逐项比对，仅在需要时才发指令。
     */
    public void restore(Connection connection) throws SQLException {
        if (connection.getAutoCommit() != autoCommit) {
            connection.setAutoCommit(autoCommit);
        }
        if (connection.getTransactionIsolation() != isolation) {
            connection.setTransactionIsolation(isolation);
        }
        if (connection.isReadOnly() != readOnly) {
            connection.setReadOnly(readOnly);
        }
    }

    boolean autoCommit() {
        return autoCommit;
    }

    int isolation() {
        return isolation;
    }

    boolean readOnly() {
        return readOnly;
    }
}
