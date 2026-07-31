package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableDescription;
import com.sondertara.joya.exceptions.DbException;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class TableMetaExtractor implements ResultSetExtractor<SchemaTableDescription> {

    @Override
    public SchemaTableDescription extract(ResultSet record) {
        try {
            Map<String, ColumnDescription> columnDescriptionMap = new LinkedCaseInsensitiveMap<>();
            ResultSetMetaData metaData = record.getMetaData();
            String tableName = metaData.getTableName(1);
            String schemaName = metaData.getSchemaName(1);
            String catalogName = metaData.getCatalogName(1);
            SchemaTableDescription tableMeta = new SchemaTableDescription();
            tableMeta.setSchema(schemaName);
            tableMeta.setTableName(tableName);
            tableMeta.setCatalog(catalogName);
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                String columnLabel = metaData.getColumnLabel(i);
                int columnType = metaData.getColumnType(i);
                String columnTypeName = metaData.getColumnTypeName(i);
                ColumnDescription columnDescription = columnDescriptionMap.get(columnName);
                if (null == columnDescription) {
                    continue;
                }
                columnDescription.setColumnName(columnName);
                columnDescription.setLabelName(metaData.getColumnLabel(i));
                columnDescription.setColumnType(columnType);
                if (0 != columnType) {
                    columnDescription.setColumnTypeName(columnTypeName);
                    columnDescription.setColumnClassName(metaData.getColumnClassName(i));
                    columnDescription.setDisplaySize(metaData.getColumnDisplaySize(i));

                    columnDescription.setPrecisionSize(metaData.getPrecision(i));

                    columnDescription.setScaleSize(metaData.getScale(i));
                    columnDescription.setAutoIncrement(metaData.isAutoIncrement(i));
                    columnDescription.setNullable(metaData.isNullable(i) != ResultSetMetaData.columnNoNulls);
                } else {
                    // 处理视图中NULL as fieldName的情况
                    columnDescription.setColumnTypeName("CHAR");
                    columnDescription.setColumnClassName(String.class.getName());
                    columnDescription.setDisplaySize(1);
                    columnDescription.setPrecisionSize(1);
                    columnDescription.setScaleSize(0);
                    columnDescription.setAutoIncrement(false);
                    columnDescription.setNullable(true);
                }
                columnDescription.setLabelName(columnLabel);

                boolean signed = false;
                try {
                    signed = metaData.isSigned(i);
                } catch (Exception ignored) {
                    // This JDBC Driver doesn't support the isSigned method
                    // nothing more we can do here by catch the exception.
                }
                columnDescription.setSigned(signed);
                columnDescriptionMap.put(columnName, columnDescription);
            }

            tableMeta.setColumns(columnDescriptionMap.values().stream().sorted(Comparator.comparing(ColumnDescription::getOrdinalPosition)).collect(Collectors.toList()));
            return tableMeta;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }
}
