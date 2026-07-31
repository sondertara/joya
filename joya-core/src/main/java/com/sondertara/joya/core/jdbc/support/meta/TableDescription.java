
package com.sondertara.joya.core.jdbc.support.meta;


import com.sondertara.common.json.JsonUtils;
import com.sondertara.joya.core.jdbc.resultset.FieldAlias;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 数据库表描述符信息定义(Table Description)
 *
 * @author tang
 */
public class TableDescription {
    @FieldAlias("TABLE_NAME")
    private String tableName;
    private String remarks;

    @Nullable
    @FieldAlias({"TABLE_CAT", "TABLE_CATALOG"})
    private String catalog;

    @Nullable
    @FieldAlias({"TABLE_SCHEM", "TABLE_SCHEMA"})
    private String schema;


    @NonNull
//  @Alias(value = "TABLE_TYPE", converter = TableTypeConverter.class)
    private TableType tableType;

    @FieldAlias("TYPE_CAT")
    private String typeCatalog;

    @FieldAlias("TYPE_SCHEM")
    private String typeSchema;

    private String typeName;

    @FieldAlias("SELF_REFERENCING_COL_NAME")
    private String selfReferencingColumnName;

    private String refGeneration;

    @Nullable
    private String sql;


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

    public void setTableType(TableType tableType) {
        this.tableType = tableType;
    }


    public String getTypeCatalog() {
        return typeCatalog;
    }

    public void setTypeCatalog(String typeCatalog) {
        this.typeCatalog = typeCatalog;
    }

    public String getTypeSchema() {
        return typeSchema;
    }

    public void setTypeSchema(String typeSchema) {
        this.typeSchema = typeSchema;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getSelfReferencingColumnName() {
        return selfReferencingColumnName;
    }

    public void setSelfReferencingColumnName(String selfReferencingColumnName) {
        this.selfReferencingColumnName = selfReferencingColumnName;
    }

    public String getRefGeneration() {
        return refGeneration;
    }

    public void setRefGeneration(String refGeneration) {
        this.refGeneration = refGeneration;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    @Override
    public String toString() {
        return JsonUtils.toPrettyString(this);
    }


    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getRemarks() {
        return this.remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public TableType getTableType() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = TableType.ofCode(tableType);
    }

    public boolean isViewTable() {
        return TableType.VIEW == tableType;
    }
}
