package com.sondertara.joya.core.model;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变查询列别名描述值对象。
 *
 * @author huangxiaohu
 * @date 2021/11/15 17:17
 * @since 1.0.0
 */
public final class ColumnAlias implements Serializable {

    @Nullable
    private final String tableName;
    private final String columnName;
    @Nullable
    private final String tableAlias;
    @Nullable
    private final String columnAlias;

    public ColumnAlias(
            @Nullable String tableName,
            String columnName,
            @Nullable String tableAlias,
            @Nullable String columnAlias
    ) {
        this.tableName = tableName;
        this.columnName = Objects.requireNonNull(columnName, "columnName must not be null");
        this.tableAlias = tableAlias;
        this.columnAlias = columnAlias;
    }

    public @Nullable String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public @Nullable String getTableAlias() {
        return tableAlias;
    }

    public @Nullable String getColumnAlias() {
        return columnAlias;
    }

    public ColumnAlias withColumnAlias(@Nullable String newColumnAlias) {
        return new ColumnAlias(tableName, columnName, tableAlias, newColumnAlias);
    }

    public String identity() {
        return Optional.ofNullable(tableAlias).map(s -> s + ".").orElse("") + columnName;
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
