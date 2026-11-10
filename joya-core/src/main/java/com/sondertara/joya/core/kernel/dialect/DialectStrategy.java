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
        return cachedInternal(dataSource);
    }

    /**
     * 自定义 {@link ConnectionProvider}（未声明固定 {@code originDataSource}）的默认策略：
     * 禁用基于 {@code builder.dataSource} 的 URL 预读，首次执行时经 {@link ConnectionProvider} 探测一次并缓存。
     *
     * <p>注意：若自定义 {@link ConnectionProvider} 为会在运行期跨异构数据库切换的动态路由提供者，
     * 请显式配置 {@link #perConnection()}，以免固定首次探测到的方言。
     */
    static DialectStrategy cachedFromProvider() {
        return cachedInternal(null);
    }

    private static DialectStrategy cachedInternal(@Nullable DataSource dataSource) {
        return new DialectStrategy() {
            private volatile Dialect resolved;

            @Override
            public Dialect resolve(ConnectionProvider connections) {
                Dialect local = resolved;
                if (local != null) {
                    return local;
                }
                Dialect probed = probe(connections);
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
        java.lang.invoke.MethodHandle handle = DataSourceUrlAccessorCache.CACHE.get(dataSource.getClass()).orElse(null);
        if (handle == null) {
            return null;
        }
        try {
            Object val = handle.invoke(dataSource);
            if (val instanceof String url && !url.isBlank()) {
                return Dialect.of(DbType.of(url));
            }
        } catch (Throwable t) {
            if (t instanceof Error err) {
                throw err;
            }
            // 数据源未暴露零连接 URL 访问器时保持惰性
        }
        return null;
    }

    /**
     * 动态连接策略：每次通过 {@link ConnectionProvider} 获取当前连接以解析方言（适用于 {@code AbstractRoutingDataSource} 跨异构库动态切换场景）。
     */
    static DialectStrategy perConnection() {
        return DialectStrategy::probe;
    }

    private static Dialect probe(ConnectionProvider connections) {
        Objects.requireNonNull(connections, "connections must not be null");
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
}

final class DataSourceUrlAccessorCache {

    private static final java.lang.invoke.MethodHandles.Lookup PUBLIC_LOOKUP = java.lang.invoke.MethodHandles.publicLookup();
    private static final String[] URL_GETTERS = {"getJdbcUrl", "getUrl", "getURL"};

    static final ClassValue<java.util.Optional<java.lang.invoke.MethodHandle>> CACHE = new ClassValue<>() {
        @Override
        protected java.util.Optional<java.lang.invoke.MethodHandle> computeValue(Class<?> type) {
            for (String getter : URL_GETTERS) {
                try {
                    var method = type.getMethod(getter);
                    if (method.getParameterCount() == 0 && CharSequence.class.isAssignableFrom(method.getReturnType())) {
                        method.setAccessible(true);
                        return java.util.Optional.of(PUBLIC_LOOKUP.unreflect(method));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            return java.util.Optional.empty();
        }
    };

    private DataSourceUrlAccessorCache() {
    }
}
