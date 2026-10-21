package com.sondertara.joya.core.query.dsl;

import java.io.Serializable;

/**
 * 8 列位置映射函数（支持 {@code Record::new} 构造器引用与编译期类型校验）。
 */
@FunctionalInterface
public interface Mapper8<A, B, C, D, E, F, G, H, V> extends Serializable {
    V apply(A a, B b, C c, D d, E e, F f, G g, H h);
}
