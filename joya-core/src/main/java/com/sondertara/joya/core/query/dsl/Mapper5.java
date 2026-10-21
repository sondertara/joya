package com.sondertara.joya.core.query.dsl;

import java.io.Serializable;

/**
 * 5 列位置映射函数（支持 {@code Record::new} 构造器引用与编译期类型校验）。
 */
@FunctionalInterface
public interface Mapper5<A, B, C, D, E, V> extends Serializable {
    V apply(A a, B b, C c, D d, E e);
}
