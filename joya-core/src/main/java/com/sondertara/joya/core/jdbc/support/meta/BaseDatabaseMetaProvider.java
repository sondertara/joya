package com.sondertara.joya.core.jdbc.support.meta;

import com.sondertara.joya.utils.Cache;

import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ListRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.TableMetaExtractor;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/11/26 16:20
 */
public class BaseDatabaseMetaProvider implements DatabaseMetaProvider {


    private final Cache<String, Map<String, TableDescription>> ALL_TABLE_CACHE = Cache.<String, Map<String, TableDescription>>newBuilder()
            .expireAfterWrite(600)
            .build();


    @Override
    public SchemaTableDescription getTableMeta(Connection connection, String catalog, String schema, String tableName) {
        return doGetTableMeta(connection, catalog, schema, tableName);
    }

    protected SchemaTableDescription doGetTableMeta(Connection connection, String catalog, String schema, String table) {

        ResultSet selectMeta = null;
        try {


            Map<String, TableDescription> allTables = findAllTables(connection, catalog, schema);

            if (!allTables.containsKey(table)) {
                allTables = findAllTables(connection, catalog, schema);
            }
            TableDescription tableDescription = allTables.get(table);

            if (null == tableDescription) {
                throw new DbException(Strings.format("Cannot find table at {}.{} by name:{}", catalog, schema, table));
            }

            final String tableName = tableDescription.getTableName();
            String catalogName = tableDescription.getCatalog();
            String schemaName = tableDescription.getSchema();
            selectMeta = connection.createStatement().executeQuery("select * from " + SqlDialectRegister.getDialect(connection).tableName(false, catalogName, schemaName, tableName) + " where 1=2");
            SchemaTableDescription schemaTableDescription = new TableMetaExtractor().extract(selectMeta);
            List<ColumnDescription> columnDescriptionList = schemaTableDescription.getColumns();

            LinkedHashMap<String, ColumnDescription> map = columnDescriptionList.stream().collect(Collectors.toMap(ColumnDescription::getColumnName, columnDescription -> columnDescription, (a, b) -> a, LinkedHashMap::new));

            DatabaseMetaData metaData = connection.getMetaData();
            // 补充一下注释信息
            try (ResultSet columns = metaData.getColumns(catalogName, schemaName, tableName, null)) {
                while (columns.next()) {
                    String columnName = columns.getString("COLUMN_NAME");
                    ColumnDescription columnDescription = map.get(columnName);
                    if (null != columnDescription) {
                        columnDescription.setRemarks(columns.getString("REMARKS"));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            Set<String> ret = new LinkedHashSet<>();
            try (ResultSet primaryKeys = metaData.getPrimaryKeys(catalogName, schemaName, tableName)) {
                while (primaryKeys.next()) {
                    ret.add(primaryKeys.getString("COLUMN_NAME"));
                }
                schemaTableDescription.setPrimaryKeys(new ArrayList<>(ret));
            }

            return schemaTableDescription;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            JdbcUtils.closeResultSet(selectMeta);
        }
    }

    private Map<String, TableDescription> findAllTables(final Connection connection, String catalog, String schema) {

        try {
            DbType type = JdbcUtils.getDatabaseType(connection);

            final String catalogName = Optional.ofNullable(catalog).orElse(connection.getCatalog());
            final String schemaName = Optional.ofNullable(schema).orElse(connection.getSchema());

            DatabaseMetaData metaData = connection.getMetaData();

            return ALL_TABLE_CACHE.get(type.name() + "@@" + catalogName + "@@" + schemaName, () -> {

                try (ResultSet tables = metaData.getTables(schemaName, schema, null, new String[]{"TABLE"})) {
                    List<TableDescription> descriptionList = new ListRecordExtractor<>(new BeanRowMapper<>(TableDescription.class)).extract(tables);

                    return descriptionList.stream().collect(Collectors.toMap(TableDescription::getTableName, tableDescription -> tableDescription, (a, b) -> a, LinkedCaseInsensitiveMap::new));

                } catch (Exception e) {
                    throw new DbException(e);
                }
            });
        } catch (Exception e) {
            throw new DbException(e);
        }
    }

}
