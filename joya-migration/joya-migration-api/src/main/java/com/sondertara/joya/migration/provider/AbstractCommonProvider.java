
package com.sondertara.joya.migration.provider;


import com.sondertara.common.base.Assert;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.migration.features.ProductFeatures;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class AbstractCommonProvider {
    public static final String NEWLINE = System.getProperty("line.separator", "\n");
    protected ProductFactoryProvider factoryProvider;
    private DataSource dataSource;
    private ProductFeatures productFeatures;

    protected final SqlDialect sqlDialect;

    protected AbstractCommonProvider(ProductFactoryProvider factoryProvider) {
        Assert.notNull(factoryProvider, "factoryProvider");
        this.factoryProvider = factoryProvider;
        this.dataSource = factoryProvider.getDataSource();
        this.productFeatures = factoryProvider.getProductFeatures();
        this.sqlDialect = SqlDialectRegister.findOrCreate(factoryProvider.getProductType());
    }

    public DbType getProductType() {
        return this.factoryProvider.getProductType();
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public <T extends ProductFeatures> T getProductFeatures() {
        return (T) productFeatures;
    }

    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        String fullTableName = sqlDialect.tableName(false, null, schemaName, tableName);
        return String.format("SELECT %s FROM %s WHERE 1=2", "*", fullTableName);
    }

    protected Map<String, Integer> getTableColumnMetaData(String schemaName, String tableName, List<String> fieldNames) {
        String fullTableName = sqlDialect.tableName(false, null, schemaName, tableName);
        String queryColumnStr = CollectionUtils.isEmpty(fieldNames) ? "*"
                : fieldNames.stream().map(s -> sqlDialect.fmtName(false, s)).collect(Collectors.joining(","));
        String sql = String.format("SELECT %s FROM %s WHERE 1=2", queryColumnStr, fullTableName);

        Map<String, Integer> columnMetaDataMap = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql);) {
                ResultSetMetaData rsMetaData = rs.getMetaData();
                for (int i = 0, len = rsMetaData.getColumnCount(); i < len; i++) {
                    columnMetaDataMap.put(rsMetaData.getColumnName(i + 1), rsMetaData.getColumnType(i + 1));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("获取表:%s.%s 的字段的元信息时失败. 请联系 DBA 核查该库、表信息.",
                            schemaName, tableName), e);
        }
        return columnMetaDataMap;
    }


}
