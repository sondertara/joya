package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.ColumnPair;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Column info for query
 *
 * @author huangxiaohu
 * @date 2021/11/15 17:17
 * @since 1.0.0
 */
@Data
public class ColumnAlias implements Serializable {

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
    /**
     * column alias
     * eg: userName
     */
    @Nullable
    private String columnAlias;

    public String identity() {
        return Optional.ofNullable(tableAlias).map(s -> s + ".").orElse("") + columnName;
    }


    public ColumnPair queryPair() {
        return new ColumnPair(identity(), columnAlias);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ColumnAlias that = (ColumnAlias) o;
        return Objects.equals(columnName, that.columnName) && Objects.equals(tableAlias, that.tableAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnName, tableAlias);
    }
}
