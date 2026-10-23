package com.sondertara.joya.core.kernel.dialect;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 方言解析器（工厂 + 缓存）：从 {@link DataSource} 惰性探测数据库类型并映射到无状态 {@link Dialect} 实例，
 * 避免每次调用都打开连接。
 */
public final class DialectResolver {

    /**
     * 弱键缓存：DataSource 被回收后条目可被 GC，避免动态数据源场景下的静态内存泄漏。
     * 并发安全由 {@link Collections#synchronizedMap} 提供。
     */
    private static final Map<DataSource, Dialect> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private DialectResolver() {
    }

    /**
     * 解析（或取缓存）指定数据源的方言。
     *
     * @param dataSource 数据源，不可为 null
     * @return 方言端口实例
     * @throws IllegalStateException 数据源不可用或方言初始化失败
     */
    public static Dialect resolve(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        return CACHE.computeIfAbsent(dataSource, DialectResolver::create);
    }

    /** 清空缓存（测试或数据源热切换时使用）。 */
    public static void clear() {
        CACHE.clear();
    }

    private static Dialect create(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DbType dbType = DbType.of(connection);
            return Dialects.forDbType(dbType);
        } catch (SQLException e) {
            throw new IllegalStateException("无法从 DataSource 解析数据库方言", e);
        }
    }
}
