package com.sondertara.joya.core.model;


import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.struct.Pair;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.JoyaJdbc;
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
    private boolean columnUppercase = JoyaJdbc.config().isColumnUppercase();

    public TableDescription getTableDescription() {
        return tableDescription;
    }

    public String getTableName() {
        if (JoyaJdbc.config().isColumnUppercase()) {
            return tableName.toUpperCase();
        } else {
            return tableName.toLowerCase();
        }
    }

    public String getPrimaryKey() {
        if (StringUtils.isBlank(primaryKey)) {
            return null;
        }
        if (columnUppercase){
            return primaryKey.toUpperCase();
        }else {
            return primaryKey.toLowerCase();
        }
    }

    public Class<?> getPrimaryKeyType() {
        return primaryKeyType;
    }


    public void setColumnFieldNameRelation(LinkedCaseInsensitiveMap<String> relation) {
        this.columnFieldNameRelation = relation;
        this.fieldNameColumnNameRelation = Maps.inverse(relation);
    }

    public Set<String> getColumnNames() {
        Set<String> list = new LinkedHashSet<>();
        boolean uppercase = JoyaJdbc.config().isColumnUppercase();
        for (Map.Entry<String, String> entry : this.columnFieldNameRelation.entrySet()) {
            list.add(uppercase ? entry.getKey().toUpperCase() : entry.getKey().toLowerCase());
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
        if (StringUtils.isBlank(columnName)) {
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
        if (StringUtils.isBlank(schemaName)) {
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
