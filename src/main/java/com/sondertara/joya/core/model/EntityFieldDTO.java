package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;

/**
 * the field of entity class
 *
 * @author huangxiaohu
 * @date 2021/11/26 16:54
 * @since 1.0.1
 */
@Data
public class EntityFieldDTO implements Serializable {

    /**
     * the  field  name of entity
     */
    private String fieldName;
    /**
     * the  column  name of table
     */
    private String columnName;

    private Boolean same;
}
