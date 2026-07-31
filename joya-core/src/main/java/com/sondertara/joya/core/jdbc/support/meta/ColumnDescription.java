
package com.sondertara.joya.core.jdbc.support.meta;

import com.sondertara.joya.core.jdbc.support.DbType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.StringJoiner;

/**
 * 数据库列描述符信息定义(Column Description)
 *
 * @author tang
 */
@EqualsAndHashCode
public class ColumnDescription implements Serializable {

    @Setter
    @Getter
    private Integer ordinalPosition;
    @Setter
    @Getter
    private Integer charOctetLength;
    private String columnName;
    private String labelName;
    private String columnTypeName;
    private String columnClassName;

    private int columnType;
    private int displaySize;
    private int scaleSize;
    private int precisionSize;
    private boolean autoIncrement;
    private boolean nullable;
    private String remarks;
    private boolean signed = false;

    @Getter
    @Setter
    private boolean pk;

    @Getter
    @Setter
    private DbType dbType;
    private String defaultValue;

    public String getColumnName() {
        if (null != this.columnName) {
            return columnName;
        }

        return this.labelName;
    }

    public void setColumnName(String fieldName) {
        this.columnName = fieldName;
    }

    public String getLabelName() {
        if (null != labelName) {
            return labelName;
        }

        return this.columnName;
    }

    public void setLabelName(String labelName) {
        this.labelName = labelName;
    }

    public String getColumnTypeName() {
        return columnTypeName;
    }

    public void setColumnTypeName(String fieldTypeName) {
        this.columnTypeName = fieldTypeName;
    }

    public String getColumnClassName() {
        return columnClassName;
    }

    public void setColumnClassName(String filedTypeClassName) {
        this.columnClassName = filedTypeClassName;
    }

    public int getColumnType() {
        return columnType;
    }

    public void setColumnType(int fieldType) {
        this.columnType = fieldType;
    }

    public int getDisplaySize() {
        return displaySize;
    }

    public void setDisplaySize(int displaySize) {
        this.displaySize = displaySize;
    }

    public int getScaleSize() {
        return scaleSize;
    }

    public void setScaleSize(int scaleSize) {
        this.scaleSize = scaleSize;
    }

    public int getPrecisionSize() {
        return precisionSize;
    }

    public void setPrecisionSize(int precisionSize) {
        this.precisionSize = precisionSize;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean isNullable) {
        this.nullable = isNullable;
    }

    public boolean isSigned() {
        return signed;
    }

    public void setSigned(boolean signed) {
        this.signed = signed;
    }

    public String getRemarks() {
        return this.remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public ColumnDescription copy() {
        ColumnDescription description = new ColumnDescription();
        description.setColumnName(columnName);
        description.setLabelName(labelName);
        description.setColumnTypeName(columnTypeName);
        description.setColumnClassName(columnClassName);
        description.setColumnType(columnType);
        description.setDisplaySize(displaySize);
        description.setScaleSize(scaleSize);
        description.setPrecisionSize(precisionSize);
        description.setAutoIncrement(autoIncrement);
        description.setNullable(nullable);
        description.setRemarks(remarks);
        description.setSigned(signed);
        description.setDbType(dbType);
        description.setDefaultValue(defaultValue);
        return description;
    }

    /////////////////////////////////////////////

    public ColumnMetaData getMetaData() {
        return new ColumnMetaData(this);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", ColumnDescription.class.getSimpleName() + "[", "]")
                .add("ordinalPosition=" + ordinalPosition)
                .add("columnName='" + columnName + "'")
                .add("labelName='" + labelName + "'")
                .add("columnTypeName='" + columnTypeName + "'")
                .add("columnClassName='" + columnClassName + "'")
                .add("columnType=" + columnType)
                .add("displaySize=" + displaySize)
                .add("scaleSize=" + scaleSize)
                .add("precisionSize=" + precisionSize)
                .add("autoIncrement=" + autoIncrement)
                .add("nullable=" + nullable)
                .add("remarks='" + remarks + "'")
                .add("signed=" + signed)
                .add("dbType=" + dbType)
                .add("defaultValue='" + defaultValue + "'")
                .toString();
    }
}
