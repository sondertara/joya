package com.sondertara.joya.core.cache;

import com.sondertara.joya.core.jdbc.support.meta.DatabaseMetaProvider;
import com.sondertara.joya.core.jdbc.support.meta.CacheableDatabaseMetaProvider;

/**
 * 缓存托管对象，持有 LocalTableMetaCache 与 CacheableDatabaseMetaProvider 的全局单例。
 *
 * @author huangxiaohu
 */
public class CacheHolder {

    private static volatile LocalTableMetaCache tableMetaCache;
    private static volatile DatabaseMetaProvider databaseMetaCache;

    public static LocalTableMetaCache tableMetaCache() {
        if (null == tableMetaCache) {
            synchronized (LocalTableMetaCache.class) {
                if (null == tableMetaCache) {
                    tableMetaCache = new LocalTableMetaCache();
                }
            }
        }
        return tableMetaCache;
    }

    /**
     * 获取数据库元数据提供者（惰性初始化）
     */
    public static DatabaseMetaProvider databaseMetaProvider() {
        if (null == databaseMetaCache) {
            synchronized (CacheHolder.class) {
                if (null == databaseMetaCache) {
                    databaseMetaCache = new CacheableDatabaseMetaProvider();
                }
            }
        }
        return databaseMetaCache;
    }

    public static void clearAll() {
        if (null != tableMetaCache) {
            tableMetaCache.destroy();
        }
        SchemaValidator.clear();
    }
}
