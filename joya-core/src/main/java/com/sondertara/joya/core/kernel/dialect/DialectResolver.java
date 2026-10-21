package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.PageSqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.utils.JdbcUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方言解析器（工厂 + 缓存）：从 {@link DataSource} 惰性探测数据库类型并缓存，避免每次调用都开连接。
 * <p>
 * 设计模式：工厂方法 + 按 {@link DataSource} 缓存的 Flyweight。
 * 线程安全：{@link ConcurrentHashMap#computeIfAbsent} 保证同一 DataSource 只探测一次。
 */
public final class DialectResolver {

    private static final Map<DataSource, Dialect> CACHE = new ConcurrentHashMap<>();

    private DialectResolver() {
    }

    /**
     * 解析（或取缓存）指定数据源的方言。
     *
     * @param dataSource 数据源，不可为 null
     * @return 方言端口实例
     * @throws IllegalStateException 数据源不可用、无法识别类型或方言初始化失败
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
            DbType dbType = JdbcUtils.getDatabaseType(connection);
            PageSqlDialect pageSqlDialect = SqlDialectRegister.getDialect(connection);
            return new JdbcDialect(dbType, pageSqlDialect);
        } catch (SQLException e) {
            throw new IllegalStateException("无法从 DataSource 解析数据库方言", e);
        }
    }
}
