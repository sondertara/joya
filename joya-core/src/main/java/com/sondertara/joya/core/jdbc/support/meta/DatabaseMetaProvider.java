package com.sondertara.joya.core.jdbc.support.meta;

import java.sql.Connection;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/11/26 16:19
 */
public interface DatabaseMetaProvider{

    SchemaTableDescription getTableMeta(Connection connection,String catalog, String schema, String tableName);

}
