package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * the data of one table
 *
 * @author huangxiaohu
 * @date 2021/11/15 16:34
 * @since 1.0.0
 */
@Data

public final class TableDTO implements Serializable {
    private String tableName;
    /**
     * the  columns map
     * key : the fieldName
     * value: the table columnName
     */
    private Map<String, String> fields;
}
