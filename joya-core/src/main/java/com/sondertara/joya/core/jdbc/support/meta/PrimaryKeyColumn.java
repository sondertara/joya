package com.sondertara.joya.core.jdbc.support.meta;

import com.sondertara.joya.core.jdbc.resultset.FieldAlias;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class PrimaryKeyColumn {
    @Nullable
    @FieldAlias({"TABLE_CAT", "TABLE_CATALOG"})
    private String catalog;

    @Nullable
    @FieldAlias({"TABLE_SCHEM", "TABLE_SCHEMA"})
    private String schema;

    @NonNull
    @FieldAlias("TABLE_NAME")
    private String tableName;

    @NonNull
    @FieldAlias("COLUMN_NAME")
    private String columnName;

    @FieldAlias("KEY_SEQ")
    private Integer keySeq;

    @Nullable
    @FieldAlias("PK_NAME")
    private String name;

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public Integer getKeySeq() {
        return keySeq;
    }

    public void setKeySeq(Integer keySeq) {
        this.keySeq = keySeq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
