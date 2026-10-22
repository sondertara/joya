package com.sondertara.joya.core.function;

/**
 * 字段级自定义类型转换器接口（用于 {@link com.sondertara.joya.core.jdbc.resultset.FieldAlias}）。
 *
 * @param <S> 源值类型
 * @param <T> 目标值类型
 */
@FunctionalInterface
public interface TypedConverter<S, T> {

    T convert(S source);

    /**
     * 默认无操作透传转换器。
     */
    final class Noop implements TypedConverter<Object, Object> {
        @Override
        public Object convert(Object source) {
            return source;
        }
    }
}
