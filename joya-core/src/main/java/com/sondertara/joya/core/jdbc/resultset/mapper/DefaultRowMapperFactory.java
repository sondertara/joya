package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.utils.SqlDataUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * 默认 {@link RowMapperFactory}：把 {@link ResultType} 解析为具体的 JDBC 映射器。
 */
public final class DefaultRowMapperFactory implements RowMapperFactory {

    public static final DefaultRowMapperFactory INSTANCE = new DefaultRowMapperFactory();

    private DefaultRowMapperFactory() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> RowMapper<V> create(ResultType type, Class<V> javaType) {
        return switch (type) {
            case SCALAR -> new SingleColumnRowMapper<>(javaType);
            case MAP -> (RowMapper<V>) MapRowMapper.INSTANCE;
            case BEAN, RECORD -> BeanRowMapper.of(javaType);
            case UNSUPPORTED -> throw new IllegalArgumentException("Cannot map rows to "
                    + (javaType == null ? "null" : javaType.getName())
                    + ": use a concrete DTO/record type, Map.class for column-name rows, or listMap()");
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> RowMapper<V> create(ResultType type,
                                   Class<V> javaType,
                                   Map<String, SqlDataUtils.ColumnReader> columnReaderOverrides) {
        if (columnReaderOverrides == null || columnReaderOverrides.isEmpty()) {
            return create(type, javaType);
        }
        return switch (type) {
            case BEAN, RECORD -> BeanRowMapper.of(javaType).withColumnReaders(columnReaderOverrides);
            case SCALAR -> {
                SqlDataUtils.ColumnReader reader = columnReaderOverrides.values().iterator().next();
                yield new RowMapper<>() {
                    @Override
                    public RowMapper<V> forResultSet(ResultSet rs) throws SQLException {
                        String colClassName = SqlDataUtils.safeGetColumnClassName(rs.getMetaData(), 1);
                        return row -> (V) reader.read(row, 1, colClassName);
                    }

                    @Override
                    public V map(ResultSet row) throws SQLException {
                        return (V) reader.read(row, 1, null);
                    }
                };
            }
            case MAP -> (RowMapper<V>) MapRowMapper.INSTANCE;
            case UNSUPPORTED -> create(type, javaType);
        };
    }
}
