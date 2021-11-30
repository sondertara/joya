package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Set;

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
     * the  field  name of entity
     */
    private Set<EntityFieldDTO> fieldNames;
}
