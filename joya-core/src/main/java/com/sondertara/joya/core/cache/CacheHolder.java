package com.sondertara.joya.core.cache;

/**
 * 实体元数据缓存入口。
 *
 * @author huangxiaohu
 */
public final class CacheHolder {

    private static final LocalTableMetaCache TABLE_META_CACHE = new LocalTableMetaCache();

    private CacheHolder() {
    }

    public static LocalTableMetaCache tableMetaCache() {
        return TABLE_META_CACHE;
    }

    public static void clearAll() {
        TABLE_META_CACHE.destroy();
    }
}
