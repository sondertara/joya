package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * @author huangxiaohu
 */
@Data
public class TableEntity implements Serializable {

    private String tableName;
    private String primaryKey;
    private Class<?> primaryKeyType;
    private Map<String,Object> data;

    private Map<String,String> relation;
}
