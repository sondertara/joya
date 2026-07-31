
package com.sondertara.joya.migration.service;


import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.IndexDescription;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableData;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableDescription;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.SourceProperties;
import com.sondertara.joya.migration.provider.meta.MetadataProvider;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.query.TableDataQueryProvider;
import com.sondertara.joya.migration.utils.GenerateSqlUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * 用DataSource对象的元数据获取服务
 *
 * @author tang
 */
public class DefaultMetaDataService extends AbstractMetaService {

    private MetadataProvider metaQueryProvider;
    private TableDataQueryProvider dataQueryProvider;

    public DefaultMetaDataService(ProductFactoryProvider factoryProvider) {
        super(factoryProvider);
        this.metaQueryProvider = factoryProvider.createMetadataQueryProvider();
        this.dataQueryProvider = factoryProvider.createTableDataQueryProvider();
    }

    @Override
    public void close() {
        DataSource dataSource = getDataSource();
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public DataSource getDataSource() {
        return this.factoryProvider.getDataSource();
    }

    @Override
    public List<String> querySchemaList() {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.querySchemaList(connection);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<TableDescription> queryTableList(String schemaName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.queryTableList(connection, schemaName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public String getTableDDL(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.getTableDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public String getTableRemark(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            TableDescription td = metaQueryProvider.queryTableMeta(connection, schemaName, tableName);
            return null == td ? null : td.getRemarks();
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public String getViewDDL(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.getViewDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<String> queryTableColumnName(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.queryTableColumnName(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<ColumnDescription> queryTableColumnMeta(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.queryTableColumnMeta(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<ColumnDescription> querySqlColumnMeta(String querySql) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.querySelectSqlColumnMeta(connection, querySql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<String> queryTablePrimaryKeys(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.queryTablePrimaryKeys(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<IndexDescription> queryTableIndexes(String schemaName, String tableName) {
        try (Connection connection = getDataSource().getConnection()) {
            return metaQueryProvider.queryTableIndexes(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public SchemaTableDescription queryTableMeta(String schemaName, String tableName) {
        SchemaTableDescription tableMeta = new SchemaTableDescription();

        try (Connection connection = getDataSource().getConnection()) {
            TableDescription tableDesc = metaQueryProvider.queryTableMeta(connection, schemaName, tableName);
            if (null == tableDesc) {
                throw new IllegalArgumentException("Table Or View Not Exist");
            }

            List<ColumnDescription> columns = metaQueryProvider.queryTableColumnMeta(
                    connection, schemaName, tableName);

            List<String> pks;
            String createSql;
            List<IndexDescription> indexes;
            if (tableDesc.isViewTable()) {
                pks = Collections.emptyList();
                createSql = metaQueryProvider.getViewDDL(connection, schemaName, tableName);
                indexes = Collections.emptyList();
            } else {
                pks = metaQueryProvider.queryTablePrimaryKeys(connection, schemaName, tableName);
                createSql = metaQueryProvider.getTableDDL(connection, schemaName, tableName);
                indexes = metaQueryProvider.queryTableIndexes(connection, schemaName, tableName);
            }

            tableMeta.setSchema(schemaName);
            tableMeta.setTableName(tableName);
            tableMeta.setTableType(tableDesc.getTableType());
            tableMeta.setRemarks(tableDesc.getRemarks());
            tableMeta.setColumns(columns);
            tableMeta.setPrimaryKeys(pks);
            tableMeta.setCreateSql(createSql);
            tableMeta.setIndexes(indexes);

            return tableMeta;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public SchemaTableData queryTableData(String schemaName, String tableName, int rowCount) {
        try (Connection connection = getDataSource().getConnection()) {
            return dataQueryProvider.queryTableData(connection, schemaName, tableName, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public void testQuerySQL(String sql) {
        try (Connection connection = getDataSource().getConnection()) {
            metaQueryProvider.testQuerySQL(connection, sql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    @Override
    public List<String> getDDLCreateTableSQL(MetadataProvider provider,
                                             List<ColumnDescription> fieldNames, List<String> primaryKeys, String schemaName,
                                             String tableName, String tableRemarks, boolean autoIncr, SourceProperties tblProperties) {
        return GenerateSqlUtils.getDDLCreateTableSQL(
                provider, fieldNames, primaryKeys, schemaName, tableName, tableRemarks, autoIncr, tblProperties);
    }

}
