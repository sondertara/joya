package com.sondertara.joya.core.cache;

import com.sondertara.common.cache.Cache;
import com.sondertara.common.cache.CacheBuilder;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.meta.BaseDatabaseMetaProvider;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableDescription;
import com.sondertara.joya.utils.JdbcUtils;

import java.sql.Connection;
import java.util.StringJoiner;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/11/26 17:48
 */
public class CacheableDatabaseMetaProvider extends BaseDatabaseMetaProvider {

    private final Cache<String, SchemaTableDescription> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(300)
            .build();


    @Override
    public SchemaTableDescription getTableMeta(Connection connection, String catalog, String schema, String tableName) {
        DbType databaseType = JdbcUtils.getDatabaseType(connection);
        StringJoiner sj = new StringJoiner("@@");
        sj.add(databaseType.name());
        sj.add(catalog);
        sj.add(schema);
        sj.add(tableName);
        return cache.get(sj.toString(), () -> doGetTableMeta(connection, catalog, schema, tableName));
    }
}
