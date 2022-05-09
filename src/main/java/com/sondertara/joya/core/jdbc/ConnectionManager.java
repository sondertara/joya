package com.sondertara.joya.core.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

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

    public ConnectionManager(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection()) {
            this.defaultCatalog = connection.getCatalog();
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
                    conn.setCatalog(defaultCatalog);
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
