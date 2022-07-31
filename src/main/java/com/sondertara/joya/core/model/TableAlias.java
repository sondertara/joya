package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;

/**
 * table alias
 *
 * @author huangxiaohu
 * @date 2021/11/21 5:11 下午
 * @since 1.0.0
 */
@Data
public class TableAlias implements Serializable {
    /**
     * the entity full class name
     */
    private String className;
    /**
     * the table name form database
     */
    private String tableName;
    /**
     * the  table alias
     */
    private String aliasName;
}
