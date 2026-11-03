package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;

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
            case MAP -> (RowMapper<V>) new MapRowMapper();
            case BEAN, RECORD -> BeanRowMapper.of(javaType);
            case UNSUPPORTED -> throw new IllegalArgumentException("Cannot map rows to "
                    + (javaType == null ? "null" : javaType.getName())
                    + ": use a concrete DTO/record type, Map.class for column-name rows, or listMap()");
        };
    }
}
