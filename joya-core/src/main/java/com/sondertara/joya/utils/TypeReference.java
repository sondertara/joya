package com.sondertara.joya.utils;

import java.lang.reflect.Type;

/**
 * 泛型类型引用，替代 common-tara 的 reflect.type.TypeReference。
 *
 * @author huangxiaohu
 */
public abstract class TypeReference<T> {

    private final Type type;

    public TypeReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof java.lang.reflect.ParameterizedType) {
            this.type = ((java.lang.reflect.ParameterizedType) superClass).getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("TypeReference must be parameterized");
        }
    }

    public Type getType() {
        return type;
    }
}
