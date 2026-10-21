package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.PageSqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.utils.JdbcUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 数据库方言解析策略：支持固定方言（零探测）、按 {@link DataSource} 惰性缓存（默认）、以及按连接动态解析（动态路由数据源场景）。
 */
@FunctionalInterface
public interface DialectStrategy {

    /**
     * 解析当前执行所需的 {@link Dialect}。
     *
     * @param connections 连接提供者
     * @return 方言端口实例
     */
    Dialect resolve(ConnectionProvider connections);

    /**
     * 固定方言策略：由显式指定的 {@link DbType} 直接构建方言，不打开任何数据库连接探测。
     */
    static DialectStrategy fixed(DbType dbType) {
        Objects.requireNonNull(dbType, "dbType must not be null");
        PageSqlDialect pageSqlDialect = (PageSqlDialect) SqlDialectRegister.findOrCreate(dbType);
        Dialect fixedDialect = new JdbcDialect(dbType, pageSqlDialect);
        return connections -> fixedDialect;
    }

    /**
     * 默认策略：按 {@link DataSource} 惰性探测一次并缓存。
     */
    static DialectStrategy cached(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        return connections -> DialectResolver.resolve(dataSource);
    }

    /**
     * 动态连接策略：每次通过 {@link ConnectionProvider} 获取当前连接以解析方言（适用于 {@code AbstractRoutingDataSource} 跨异构库动态切换场景）。
     */
    static DialectStrategy perConnection() {
        return connections -> {
            Connection conn = null;
            try {
                conn = connections.acquire();
                DbType dbType = JdbcUtils.getDatabaseType(conn);
                PageSqlDialect pageSqlDialect = (PageSqlDialect) SqlDialectRegister.findOrCreate(dbType);
                return new JdbcDialect(dbType, pageSqlDialect);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to resolve dialect from ConnectionProvider", e);
            } finally {
                if (conn != null) {
                    try {
                        connections.release(conn);
                    } catch (SQLException ignored) {
                    }
                }
            }
        };
    }
}
