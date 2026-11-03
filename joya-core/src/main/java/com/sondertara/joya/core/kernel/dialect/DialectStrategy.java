package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 数据库方言解析策略：支持固定方言（零探测）、按客户端实例惰性探测缓存（默认）、以及按连接动态解析（动态路由数据源场景）。
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
     * 仅返回已解析缓存的 {@link Dialect}（不打开任何数据库连接探测）。
     */
    default @Nullable Dialect peek() {
        return null;
    }

    /**
     * 固定方言策略：由显式指定的 {@link DbType} 直接获取预置方言，不打开任何数据库连接探测。
     */
    static DialectStrategy fixed(DbType dbType) {
        Objects.requireNonNull(dbType, "dbType must not be null");
        return fixed(Dialect.of(dbType));
    }

    /**
     * 固定方言策略：直接使用自定义或预置 {@link Dialect} 实例。
     */
    static DialectStrategy fixed(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        return new DialectStrategy() {
            @Override
            public Dialect resolve(ConnectionProvider connections) {
                return dialect;
            }

            @Override
            public Dialect peek() {
                return dialect;
            }
        };
    }

    /**
     * 默认策略：按客户端绑定 {@link DataSource} 惰性探测一次并缓存在当前策略实例内。
     */
    static DialectStrategy cached(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        return new DialectStrategy() {
            private volatile Dialect resolved;

            @Override
            public Dialect resolve(ConnectionProvider connections) {
                Dialect local = resolved;
                if (local != null) {
                    return local;
                }
                Dialect probed = probe(dataSource, connections);
                synchronized (this) {
                    if (resolved == null) {
                        resolved = probed;
                    }
                    return resolved;
                }
            }

            @Override
            public @Nullable Dialect peek() {
                Dialect local = resolved;
                if (local != null) {
                    return local;
                }
                Dialect fromUrl = peekFromDataSourceUrl(dataSource);
                if (fromUrl != null) {
                    synchronized (this) {
                        if (resolved == null) {
                            resolved = fromUrl;
                        }
                        return resolved;
                    }
                }
                return null;
            }
        };
    }

    private static @Nullable Dialect peekFromDataSourceUrl(@Nullable DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        for (String getter : new String[]{"getJdbcUrl", "getUrl", "getURL"}) {
            try {
                var method = dataSource.getClass().getMethod(getter);
                Object val = method.invoke(dataSource);
                if (val instanceof String url && !url.isBlank()) {
                    return Dialect.of(DbType.of(url));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 数据源未暴露零连接 URL 访问器时保持惰性
            }
        }
        return null;
    }

    /**
     * 动态连接策略：每次通过 {@link ConnectionProvider} 获取当前连接以解析方言（适用于 {@code AbstractRoutingDataSource} 跨异构库动态切换场景）。
     */
    static DialectStrategy perConnection() {
        return connections -> probe(null, Objects.requireNonNull(connections, "connections must not be null"));
    }

    private static Dialect probe(@Nullable DataSource dataSource, @Nullable ConnectionProvider connections) {
        if (connections != null) {
            Connection conn = null;
            try {
                conn = connections.acquire();
                return Dialect.of(DbType.of(conn));
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
        }
        if (dataSource == null) {
            return Dialect.DEFAULT;
        }
        try (Connection connection = dataSource.getConnection()) {
            return Dialect.of(DbType.of(connection));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve dialect from DataSource", e);
        }
    }
}
