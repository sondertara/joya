package com.sondertara.joya.core.model;


import com.sondertara.common.collection.LinkedCaseInsensitiveMap;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import lombok.Setter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * the table data entity
 *
 * @author huangxiaohu
 */

public class EntityTableMetaDefinition implements Serializable, Cloneable {

    @Setter
    private TableDescription tableDescription;


    private Class<?> entityClass;

    @Setter
    @Nullable
    private String schemaName;


    /**
     * the table name
     */
    @Setter
    @NonNull
    private String tableName;
    /**
     * primary key name if exist
     */
    @Setter
    private String primaryKey;
    /**
     * the type of primary key
     */
    @Setter
    private Class<?> primaryKeyType;
    /**
     * the relation of column and the field
     * <p>
     * key is table column name ,value is the field name
     */

    private LinkedCaseInsensitiveMap<String> columnFieldNameRelation;
    private Map<String, String> fieldNameColumnNameRelation;
    /**
     * the column class type
     * key is table column name
     */
    private LinkedCaseInsensitiveMap<Field> columnFieldRelation;

    @Setter
    private boolean columnUppercase = JoyaClient.config().isColumnUppercase();

    public TableDescription getTableDescription() {
        return tableDescription;
    }

    public String getTableName() {
        if (JoyaClient.config().isTableUppercase()) {
            return tableName.toUpperCase();
        } else {
            return tableName.toLowerCase();
        }
    }

    public String getPrimaryKey() {
        if (Strings.isBlank(primaryKey)) {
            return null;
        }
        if (columnUppercase) {
            return primaryKey.toUpperCase();
        } else {
            return primaryKey.toLowerCase();
        }
    }

    public Class<?> getPrimaryKeyType() {
        return primaryKeyType;
    }


    public void setColumnFieldNameRelation(LinkedCaseInsensitiveMap<String> relation) {
        this.columnFieldNameRelation = relation;
        this.fieldNameColumnNameRelation = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : relation.entrySet()) {
            this.fieldNameColumnNameRelation.put(entry.getValue(), entry.getKey());
        }
    }

    public Set<String> getColumnNames() {
        Set<String> list = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : this.columnFieldNameRelation.entrySet()) {
            list.add(columnUppercase ? entry.getKey().toUpperCase() : entry.getKey().toLowerCase());
        }
        return list;
    }

    public boolean containColumn(String columnName) {
        return this.columnFieldNameRelation.containsKey(columnName);
    }

    public boolean containFiled(String fieldName) {
        return this.fieldNameColumnNameRelation.containsKey(fieldName);
    }


    public String findColumnNameByField(String fieldName) {
        return formatColumnName(this.fieldNameColumnNameRelation.get(fieldName));
    }

    public String findFieldNameByColumn(String columnName) {
        return this.columnFieldNameRelation.get(columnName);
    }


    public Pair<String, String> findColumnFiledNamePairByColumn(String columnName) {
        String s = this.columnFieldNameRelation.get(columnName);
        if (null == s) {
            return null;

        }
        return Pair.of(formatColumnName(columnName), s);
    }


    public Map<String, Field> getColumnFiledPairs() {
        Map<String, Field> list = new LinkedHashMap<>();
        this.columnFieldRelation.forEach((k, v) -> list.put(formatColumnName(k), v));
        return list;
    }

    public Field findFieldByColumn(String columnName) {
        return this.columnFieldRelation.get(columnName);
    }


    private String formatColumnName(String columnName) {
        if (Strings.isBlank(columnName)) {
            return null;
        }
        if (columnUppercase) {
            return columnName.toUpperCase();
        } else {
            return columnName.toLowerCase();
        }
    }


    public Set<String> getFieldNames() {
        Set<String> list = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : this.columnFieldNameRelation.entrySet()) {
            list.add(entry.getValue());
        }
        return list;
    }

    public List<Field> getFields() {
        List<Field> list = new ArrayList<>();
        for (Map.Entry<String, Field> entry : this.columnFieldRelation.entrySet()) {
            list.add(entry.getValue());
        }
        return list;
    }


    public void setColumns(LinkedCaseInsensitiveMap<Field> columns) {
        this.columnFieldRelation = columns;
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public void setEntityClass(Class<?> entityClass) {
        this.entityClass = entityClass;
    }

    public String getSchemaName() {
        if (Strings.isBlank(schemaName)) {
            return null;
        }
        if (columnUppercase) {
            return schemaName.toUpperCase();
        } else {
            return schemaName.toLowerCase();
        }
    }

    @Override
    public EntityTableMetaDefinition clone() {
        EntityTableMetaDefinition metaDefinition = new EntityTableMetaDefinition();
        metaDefinition.setColumns(columnFieldRelation);
        metaDefinition.setColumnFieldNameRelation(columnFieldNameRelation);

        metaDefinition.setEntityClass(entityClass);
        metaDefinition.setTableDescription(tableDescription);
        metaDefinition.setSchemaName(schemaName);
        metaDefinition.setTableName(tableName);
        metaDefinition.setPrimaryKey(primaryKey);
        metaDefinition.setPrimaryKeyType(primaryKeyType);
        return metaDefinition;
    }
}
