package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.pagination.OrderParam;
import lombok.Data;

import java.io.Serializable;
import java.util.Optional;

@Data
public class OrderByAlias implements Serializable {
    private final OrderParam.OrderBy orderBy;
    /**
     * table name of database
     * eg: user
     */
    private String tableName;

    /**
     * column name form database which has parsed to lowercase
     * eg: user_name
     */
    private String columnName;

    /**
     * table alias
     * eg: t0
     */
    private String tableAlias;

    public String identity() {
        return Optional.ofNullable(tableAlias).map(s -> s + ".").orElse("") + columnName;
    }


    public OrderByAlias(OrderParam.OrderBy orderBy) {
        this.orderBy = orderBy;
    }

}
