package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * 按位置映射：通过 {@link #forResultSet(ResultSet)} 预解析列元数据并使用预绑定 {@link SqlDataUtils.ColumnReader} 单次取值，
 * 再整体交给构造器（{@code X::new}）或映射函数。
 *
 * @param <V> 行结果类型
 */
final class PositionalRowMapper<V> implements RowMapper<V> {

    private final SqlDataUtils.ColumnReader[] readers;
    /** 目标 record 构造参数对应的基本类型默认值（非基本类型槽位为 null）。 */
    private final Object @Nullable [] primitiveDefaults;
    private final Function<Object[], V> factory;

    PositionalRowMapper(Class<?>[] columnTypes, Class<?> @Nullable [] parameterTypes, Function<Object[], V> factory) {
        this(columnTypes, parameterTypes, null, factory);
    }

    PositionalRowMapper(Class<?>[] columnTypes,
                        Class<?> @Nullable [] parameterTypes,
                        @Nullable ColumnConverter @Nullable [] converters,
                        Function<Object[], V> factory) {
        this.readers = new SqlDataUtils.ColumnReader[columnTypes.length];
        for (int i = 0; i < columnTypes.length; i++) {
            ColumnConverter conv = (converters != null && i < converters.length) ? converters[i] : null;
            this.readers[i] = ColumnValues.readerFor(columnTypes[i], conv);
        }
        if (parameterTypes != null) {
            Object[] defaults = new Object[columnTypes.length];
            boolean hasAnyPrimitive = false;
            for (int i = 0; i < columnTypes.length && i < parameterTypes.length; i++) {
                if (parameterTypes[i] != null && parameterTypes[i].isPrimitive()) {
                    defaults[i] = TypeUtils.defaultValue(parameterTypes[i]);
                    hasAnyPrimitive = true;
                }
            }
            this.primitiveDefaults = hasAnyPrimitive ? defaults : null;
        } else {
            this.primitiveDefaults = null;
        }
        this.factory = factory;
    }

    @Override
    public RowMapper<V> forResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int metaCols = metaData != null ? metaData.getColumnCount() : 0;
        String[] colClassNames = new String[readers.length];
        for (int i = 0; i < readers.length && i < metaCols; i++) {
            colClassNames[i] = SqlDataUtils.safeGetColumnClassName(metaData, i + 1);
        }
        return row -> mapWithColClasses(row, colClassNames);
    }

    @Override
    public V map(ResultSet record) throws SQLException {
        return mapWithColClasses(record, null);
    }

    private V mapWithColClasses(ResultSet record, String @Nullable [] colClassNames) throws SQLException {
        Object[] args = new Object[readers.length];
        for (int i = 0; i < readers.length; i++) {
            String colClass = colClassNames != null ? colClassNames[i] : null;
            Object value = readers[i].read(record, i + 1, colClass);
            if (value == null && primitiveDefaults != null) {
                value = primitiveDefaults[i];
            }
            args[i] = value;
        }
        return factory.apply(args);
    }
}
