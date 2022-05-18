package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * the table data entity
 *
 * @author huangxiaohu
 */
@Data
public class TableEntity implements Serializable {

    /**
     * the table name
     */
    private String tableName;
    /**
     * primary key name if exist
     */
    private String primaryKey;
    /**
     * the type of primary key
     */
    private Class<?> primaryKeyType;
    /**
     * the row data
     * key is the column name
     */
    private Map<String, Object> data;
    /**
     * the relation of column and the field
     *
     * key is column name ,value is the field name
     */
    private Map<String, String> relation;
}
