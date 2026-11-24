package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.utils.SqlDataUtils;

import java.util.Map;

/**
 * 结果类型到 {@link RowMapper} 的解析端口。
 *
 * <p>kernel 不应依赖 {@code jdbc} 适配层，因此这里只定义端口；默认实现
 * {@code jdbc.resultset.mapper.DefaultRowMapperFactory} 由 {@code JoyaClient} 注入，
 * {@code query} 渲染层只携带 {@link ResultType} 与目标类型，不再直接构造映射器。
 */
public interface RowMapperFactory {

    /**
     * 创建行映射器。
     *
     * @param type     结果类型分类
     * @param javaType 目标 Java 类型
     * @param <V>      行结果类型
     */
    <V> RowMapper<V> create(ResultType type, Class<V> javaType);

    /**
     * 创建附带源列自定义读取器的行映射器（例如查询投影向未标注 {@code @Convert} 的普通 DTO/Record
     * 透传源实体列的 {@code ColumnConverter} 读取器）；默认实现退回 {@link #create(ResultType, Class)}。
     *
     * @param type                  结果类型分类
     * @param javaType              目标 Java 类型
     * @param columnReaderOverrides 按列别名/属性名索引的列读取器覆盖表
     */
    default <V> RowMapper<V> create(ResultType type,
                                    Class<V> javaType,
                                    Map<String, SqlDataUtils.ColumnReader> columnReaderOverrides) {
        return create(type, javaType);
    }

    /**
     * 最小默认实现：仅支持标量；用于直接构造 {@code ExecutionKernel} 的底层测试或场景。
     */
    RowMapperFactory DEFAULT = new RowMapperFactory() {
        @Override
        public <V> RowMapper<V> create(ResultType type, Class<V> javaType) {
            if (type == ResultType.SCALAR) {
                return new SingleColumnRowMapper<>(javaType);
            }
            throw new IllegalStateException("No RowMapperFactory configured for result type " + type
                    + " and java type " + (javaType == null ? "null" : javaType.getName()));
        }
    };
}
