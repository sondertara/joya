package com.sondertara.joya.core.jdbc;

import com.sondertara.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

import static com.sondertara.joya.core.constant.JoyaConst.SQL.ORACLE_GET_CURRENT_SCHEMA;
import static com.sondertara.joya.core.constant.JoyaConst.SQL.ORACLE_SET_CURRENT_SCHEMA;

/**
 * 连接管理器
 * 封装了线程安全的数据库连接
 *
 * @author huangxiaohu
 */
public final class ConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(JoyaJdbc.class);
    private final DataSource dataSource;
    private final ThreadLocal<Connection> connHolder = new ThreadLocal<>();

    private volatile String defaultCatalog;

    private volatile DbType dbType;

    public ConnectionManager(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            if (StringUtils.containsAnyIgnoreCase(productName, DbType.ORACLE.getType())) {
                dbType = DbType.ORACLE;
                ResultSet resultSet = connection.createStatement().executeQuery(ORACLE_GET_CURRENT_SCHEMA);
                if (resultSet.next()) {
                    this.defaultCatalog = resultSet.getString(1);
                }
            } else {
                this.defaultCatalog = connection.getCatalog();
            }
        } catch (SQLException e) {
            LOG.error("Get catalog failed.", e);
        }
    }

    public void destroy(Consumer<DataSource> consumer) {
        consumer.accept(this.dataSource);
    }

    public synchronized Connection getConnection() {
        try {
            Connection conn = connHolder.get();
            if (conn == null || conn.isClosed()) {
                conn = dataSource.getConnection();
                if (null != defaultCatalog) {
                    if (DbType.ORACLE.equals(dbType)) {
                        conn.createStatement().execute(ORACLE_SET_CURRENT_SCHEMA.replace("?", defaultCatalog));
                    } else {
                        conn.setCatalog(defaultCatalog);
                    }
                }
                connHolder.set(conn);
            }
            return conn;
        } catch (SQLException e) {
            throw new DbException("An error occurred while creating a database connection.", e);
        }
    }

    public void close(Connection conn) {
        if (conn != null) {
            try {
                if (conn.getAutoCommit()) {
                    conn.close();
                    connHolder.remove();
                }
            } catch (SQLException ignored) {
            }
        }
    }

    public void close(Connection conn, Statement stmt, ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignored) {
            }
        }
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignored) {
            }
        }
        if (conn != null) {
            try {
                if (conn.getAutoCommit()) {
                    conn.close();
                    connHolder.remove();
                }
            } catch (SQLException ignored) {
            }
        }
    }

    public void startTransaction() {
        try {
            Connection conn = connHolder.get();
            if (conn != null) {
                conn.close();
                connHolder.remove();
            }
            conn = dataSource.getConnection();
            if (null != defaultCatalog) {
                if (DbType.ORACLE.equals(dbType)) {
                    conn.createStatement().execute(ORACLE_SET_CURRENT_SCHEMA.replace("?", defaultCatalog));
                } else {
                    conn.setCatalog(defaultCatalog);
                }
            }
            conn.setAutoCommit(false);
            connHolder.set(conn);
        } catch (SQLException e) {
            throw new DbException("An error occurred while starting transaction.", e);
        }
    }

    public void commit() {
        Connection conn = connHolder.get();
        if (conn != null) {
            try {
                conn.commit();
                conn.close();
                connHolder.remove();
            } catch (SQLException e) {
                throw new DbException("An error occurred while committing transaction.", e);
            }
        }
    }

    public void rollback() {
        Connection conn = connHolder.get();
        if (conn != null) {
            try {
                conn.rollback();
                conn.close();
                connHolder.remove();
            } catch (SQLException e) {
                throw new DbException("An error occurred while committing transaction.", e);
            }
        }
    }

    public boolean inTransaction() {
        Connection conn = connHolder.get();
        try {
            return conn != null && !conn.getAutoCommit();
        } catch (SQLException e) {
            throw new DbException("An error occurred while getting auto commit.", e);
        }
    }
}
