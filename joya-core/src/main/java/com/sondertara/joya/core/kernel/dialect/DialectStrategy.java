package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.statement.DbType;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 数据库方言解析策略：支持固定方言（零探测）、按客户端实例惰性单飞缓存（默认）、以及按连接动态解析。
 */
@FunctionalInterface
public interface DialectStrategy {

    Dialect resolve(ConnectionProvider connections);

    default @Nullable Dialect peek() {
        return null;
    }

    static DialectStrategy fixed(DbType dbType) {
        Objects.requireNonNull(dbType, "dbType must not be null");
        return fixed(Dialect.of(dbType));
    }

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

    static DialectStrategy cached(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        return cachedInternal(dataSource);
    }

    static DialectStrategy cachedFromProvider() {
        return cachedInternal(null);
    }

    private static DialectStrategy cachedInternal(@Nullable DataSource dataSource) {
        return new DialectStrategy() {
            private final Object probeLock = new Object();
            private volatile @Nullable Dialect resolved;

            @Override
            public Dialect resolve(ConnectionProvider connections) {
                Dialect local = resolved;
                if (local != null) {
                    return local;
                }
                Dialect fromUrl = peek();
                if (fromUrl != null) {
                    return fromUrl;
                }
                synchronized (probeLock) {
                    local = resolved;
                    if (local == null) {
                        local = probe(connections);
                        resolved = local;
                    }
                    return local;
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
                    synchronized (probeLock) {
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
        MethodHandle handle = DataSourceUrlAccessorCache.CACHE.get(dataSource.getClass()).orElse(null);
        if (handle == null) {
            return null;
        }
        try {
            Object val = handle.invoke(dataSource);
            if (val instanceof CharSequence seq) {
                String url = seq.toString();
                if (!url.isBlank()) {
                    return Dialect.of(DbType.of(url));
                }
            }
        } catch (Throwable t) {
            if (t instanceof Error err) {
                throw err;
            }
        }
        return null;
    }

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

    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
    private static final String[] URL_GETTERS = {"getJdbcUrl", "getUrl", "getURL"};

    static final ClassValue<Optional<MethodHandle>> CACHE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> type) {
            for (String getter : URL_GETTERS) {
                try {
                    Method method = type.getMethod(getter);
                    if (method.getParameterCount() == 0 && CharSequence.class.isAssignableFrom(method.getReturnType())) {
                        try {
                            return Optional.of(PUBLIC_LOOKUP.unreflect(method));
                        } catch (IllegalAccessException nonExported) {
                            if (method.trySetAccessible()) {
                                return Optional.of(MethodHandles.lookup().unreflect(method));
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
            return Optional.empty();
        }
    };

    private DataSourceUrlAccessorCache() {
    }
}
