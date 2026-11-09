package com.sondertara.joya.core.kernel.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 连接自动提交状态跟踪器（{@link ConnectionProvider} 实现复用）。
 *
 * <p>某些连接池默认 {@code autoCommit=false}，单条自动提交执行前需要临时切为 {@code true}，
 * 归还前再恢复原值。本类只负责这一份状态记录，避免各 Provider 各自维护一份 Identity Set：
 * <ul>
 *   <li>{@link #flipToAutoCommit(Connection)}：仅在当前为手动提交时切换并登记；</li>
 *   <li>{@link #restore(Connection)}：仅恢复本类登记过的连接；</li>
 *   <li>{@link #forget(Connection)}：连接被丢弃 / 由外部基础设施接管时清除登记。</li>
 * </ul>
 *
 * <p>框架内部组件；业务代码不应直接使用。
 */
public final class AutoCommitFlipTracker {

    private final Set<Connection> flipped = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * 若连接当前为 {@code autoCommit=false}，切换为 {@code true} 并登记。
     *
     * @return 是否发生了切换
     */
    public boolean flipToAutoCommit(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) {
            return false;
        }
        connection.setAutoCommit(true);
        flipped.add(connection);
        return true;
    }

    /**
     * 恢复由 {@link #flipToAutoCommit(Connection)} 切换过的连接为 {@code autoCommit=false}。
     *
     * @return 是否发生了恢复
     */
    public boolean restore(Connection connection) throws SQLException {
        if (!flipped.remove(connection)) {
            return false;
        }
        connection.setAutoCommit(false);
        return true;
    }

    /** 清除连接登记（连接被销毁 / 移交外部管理时使用），不做任何 JDBC 操作。 */
    public void forget(Connection connection) {
        flipped.remove(connection);
    }
}
