package com.sondertara.joya.core.jdbc.resultset;

import com.sondertara.joya.core.function.TypedConverter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 为结果集映射属性声明一个或多个候选列名别名，以及可选的自定义值转换器 {@link TypedConverter}。
 */
@Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldAlias {
    /**
     * 候选列名别名列表。
     */
    String[] value() default {};

    @SuppressWarnings("rawtypes")
    Class<? extends TypedConverter> converter() default TypedConverter.Noop.class;
}
