package com.sondertara.joya.core.model;

import lombok.Data;

import java.io.Serializable;

/**
 * TODO
 *
 * @author huangxiaohu
 * @date 2021/11/15 17:17
 * @since
 */
@Data
public class ColumnDTO implements Serializable {

    private String tableName;

    private String columnName;

    private String tableAlias;
    private String columnAlias;
}
