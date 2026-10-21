package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;

import java.sql.ResultSet;
import java.util.function.Function;

/**
 * 按位置映射：第 i 列按第 i 个选择列的声明类型取值，再整体交给构造器（{@code X::new}）或映射函数。
 *
 * @param <V> 行结果类型
 */
final class PositionalRowMapper<V> implements RowMapper<V> {

    private final Class<?>[] columnTypes;
    /** 目标 record 构造参数类型（可为 null）；用于基本类型参数遇到 SQL NULL 时回填默认值。 */
    private final Class<?>[] parameterTypes;
    private final Function<Object[], V> factory;

    PositionalRowMapper(Class<?>[] columnTypes, Class<?>[] parameterTypes, Function<Object[], V> factory) {
        this.columnTypes = columnTypes.clone();
        this.parameterTypes = parameterTypes == null ? null : parameterTypes.clone();
        this.factory = factory;
    }

    @Override
    public V map(ResultSet record) {
        Object[] args = new Object[columnTypes.length];
        for (int i = 0; i < columnTypes.length; i++) {
            Object value = ColumnValues.read(record, i + 1, columnTypes[i]);
            if (value == null && parameterTypes != null && i < parameterTypes.length) {
                value = ColumnValues.primitiveDefault(parameterTypes[i]);
            }
            args[i] = value;
        }
        return factory.apply(args);
    }
}
