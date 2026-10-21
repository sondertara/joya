package com.sondertara.joya.core.tx;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 连接探针 —— 用 JDK 动态代理伪造 {@link DataSource} 与 {@link Connection}，
 * 精确记录事务管理器对连接发出的每一条指令。
 *
 * <p>用途：把 {@code 01-assessment.md} 里"断言连接调用序列"的探针固化为正式测试工具。
 * 事务正确性的很多缺陷（隐式提交、隔离级别未还原、连接提前关闭）
 * <b>只能从连接指令序列观察</b>，无法从 SQL 结果观察。
 *
 * <p>记录的指令记法（与评估文档一致）：
 * <ul>
 *   <li>{@code setAutoCommit(false)} / {@code setAutoCommit(true)}</li>
 *   <li>{@code COMMIT} / {@code ROLLBACK} / {@code ROLLBACK_TO_SP}</li>
 *   <li>{@code SAVEPOINT} / {@code RELEASE_SP}</li>
 *   <li>{@code CLOSE}</li>
 *   <li>{@code isolation=READ_COMMITTED}（隔离级别设置，含可读名称）</li>
 *   <li>{@code setReadOnly(true)}</li>
 * </ul>
 *
 * <p>状态语义（重要）：
 * <ul>
 *   <li>连接状态（autoCommit / isolation）在 spy 内<b>持久保存、不随借出重置</b>，
 *       以复现"上一个使用者污染下一个使用者"这类缺陷（P1-1）。</li>
 *   <li>{@code getConnection()} 总是返回同一个 {@link Connection} 代理，
 *       模拟"同一事务内复用同一物理连接"（不变量 I5 的观测基础）。</li>
 * </ul>
 */
public final class ConnectionSpy {

    private final List<String> ops = new ArrayList<>();

    /** 池的配置值：借出时连接的初始 autoCommit（模拟池配置）。 */
    private final boolean poolAutoCommit;
    private final int poolIsolation;

    private int borrowCount;
    private int closeCount;
    private int isValidCount;
    private int commitCount;
    private int rollbackCount;

    /** 连接当前状态（跨借出保持，不重置）。 */
    private volatile boolean autoCommit;
    private volatile int isolation;
    private volatile boolean closed;

    private final DataSource dataSource;
    private final Connection connection;

    private ConnectionSpy(boolean poolAutoCommit, int poolIsolation) {
        this.poolAutoCommit = poolAutoCommit;
        this.poolIsolation = poolIsolation;
        this.autoCommit = poolAutoCommit;
        this.isolation = poolIsolation;
        this.connection = newConnectionProxy();
        this.dataSource = newDataSourceProxy();
    }

    // ===== 工厂：模拟两种池配置 =====

    /** 池默认 autoCommit=true（常规池）。 */
    public static ConnectionSpy autoCommitPool() {
        return new ConnectionSpy(true, Connection.TRANSACTION_READ_COMMITTED);
    }

    /** 池默认 autoCommit=false（如 Druid 默认）—— P0-3 的触发条件。 */
    public static ConnectionSpy manualCommitPool() {
        return new ConnectionSpy(false, Connection.TRANSACTION_READ_COMMITTED);
    }

    public static ConnectionSpy withPool(boolean poolAutoCommit, int poolIsolation) {
        return new ConnectionSpy(poolAutoCommit, poolIsolation);
    }

    // ===== 对外观测 =====

    /** 伪造的数据源，可安全传给 ConnectionContext / TransactionManager。 */
    public DataSource dataSource() {
        return dataSource;
    }

    /** 底层连接代理（便于断言状态）。 */
    public Connection connection() {
        return connection;
    }

    /** 连接指令序列（不可变快照）。 */
    public List<String> ops() {
        return Collections.unmodifiableList(new ArrayList<>(ops));
    }

    /** 指令序列的字符串形式，便于断言与失败信息展示。 */
    public String opsAsString() {
        return String.join(", ", ops);
    }

    public int borrowCount() {
        return borrowCount;
    }

    public int closeCount() {
        return closeCount;
    }

    public int isValidCount() {
        return isValidCount;
    }

    public int commitCount() {
        return commitCount;
    }

    public int rollbackCount() {
        return rollbackCount;
    }

    public boolean currentAutoCommit() {
        return autoCommit;
    }

    public int currentIsolation() {
        return isolation;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 清空记录，保留当前连接状态（用于分段观测）。 */
    public void reset() {
        ops.clear();
    }

    // ===== 代理实现 =====

    private DataSource newDataSourceProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                borrowCount++;
                return connection;
            }
            return defaultValue(method.getReturnType());
        };
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                handler);
    }

    private Connection newConnectionProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "setAutoCommit": {
                    boolean v = (Boolean) args[0];
                    autoCommit = v;
                    ops.add("setAutoCommit(" + v + ")");
                    return null;
                }
                case "getAutoCommit":
                    return autoCommit;
                case "commit":
                    commitCount++;
                    ops.add("COMMIT");
                    return null;
                case "rollback":
                    if (args != null && args.length == 1) {
                        ops.add("ROLLBACK_TO_SP");
                    } else {
                        rollbackCount++;
                        ops.add("ROLLBACK");
                    }
                    return null;
                case "setSavepoint":
                    ops.add("SAVEPOINT");
                    return newFakeSavepoint();
                case "releaseSavepoint":
                    ops.add("RELEASE_SP");
                    return null;
                case "close":
                    closeCount++;
                    closed = true;
                    ops.add("CLOSE");
                    return null;
                case "isClosed":
                    return closed;
                case "isValid":
                    isValidCount++;
                    return true;
                case "setTransactionIsolation": {
                    isolation = (Integer) args[0];
                    ops.add("isolation=" + isolationName(isolation));
                    return null;
                }
                case "getTransactionIsolation":
                    return isolation;
                case "setReadOnly":
                    ops.add("setReadOnly(" + args[0] + ")");
                    return null;
                case "isReadOnly":
                    return false;
                case "prepareStatement":
                    ops.add("EXEC");
                    return newPreparedStatementProxy();
                case "getMetaData":
                    return newDatabaseMetaDataProxy();
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
    }

    /**
     * 语句代理：只支持事务测试所需的最小行为
     * （executeUpdate / executeQuery / getGeneratedKeys / close）。
     */
    private java.sql.PreparedStatement newPreparedStatementProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeUpdate":
                    return 1;
                case "executeQuery":
                    return newResultSetProxy();
                case "getGeneratedKeys":
                    return newResultSetProxy();
                case "getConnection":
                    return connection;
                case "close":
                    return null;
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (java.sql.PreparedStatement) Proxy.newProxyInstance(
                java.sql.PreparedStatement.class.getClassLoader(),
                new Class<?>[]{java.sql.PreparedStatement.class},
                handler);
    }

    /** 结果集代理：始终为空（{@code next()} 返回 false）。 */
    private java.sql.ResultSet newResultSetProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return false;
                case "close":
                    return null;
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (java.sql.ResultSet) Proxy.newProxyInstance(
                java.sql.ResultSet.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class},
                handler);
    }

    private java.sql.DatabaseMetaData newDatabaseMetaDataProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "supportsSavepoints":
                    return true;
                case "getDatabaseProductName":
                    return "SPY";
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (java.sql.DatabaseMetaData) Proxy.newProxyInstance(
                java.sql.DatabaseMetaData.class.getClassLoader(),
                new Class<?>[]{java.sql.DatabaseMetaData.class},
                handler);
    }

    private static Savepoint newFakeSavepoint() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getSavepointId":
                    return 1;
                case "getSavepointName":
                    return "SPY_SP";
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Savepoint) Proxy.newProxyInstance(
                Savepoint.class.getClassLoader(),
                new Class<?>[]{Savepoint.class},
                handler);
    }

    static String isolationName(int level) {
        switch (level) {
            case Connection.TRANSACTION_NONE:
                return "NONE";
            case Connection.TRANSACTION_READ_UNCOMMITTED:
                return "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED:
                return "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ:
                return "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE:
                return "SERIALIZABLE";
            default:
                return "UNKNOWN_" + level;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == void.class) {
            return null;
        }
        return null;
    }
}
