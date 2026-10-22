package com.sondertara.joya.core.function;

import java.io.Serializable;

/**
 * 可序列化的实体属性 Getter 方法引用函数接口（如 {@code User::getUserName}）。
 *
 * @param <T> 实体或目标类型
 * @param <R> 属性返回值类型
 */
@FunctionalInterface
public interface IGetter<T, R> extends Serializable {

    R apply(T source);
}
