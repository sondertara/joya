package com.sondertara.joya.core.model;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;

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
     * the table name form database
     */
    private String tableName;
    /**
     * the  table alias
     */
    private String aliasName;
    @Nullable
    private Class<?> entityClass;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TableAlias that = (TableAlias) o;
        return Objects.equals(tableName, that.tableName) && Objects.equals(aliasName, that.aliasName);
    }

    public static TableAlias of(String tableName, String aliasName) {
        TableAlias tableAlias = new TableAlias();
        tableAlias.setTableName(tableName);
        tableAlias.setAliasName(aliasName);
        return tableAlias;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, aliasName);
    }
}
