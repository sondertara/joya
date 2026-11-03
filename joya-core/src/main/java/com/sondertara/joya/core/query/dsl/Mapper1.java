package com.sondertara.joya.core.query.dsl;

import java.io.Serializable;
import java.util.function.Function;

/**
 * 1 列位置映射函数（支持 {@code Record::new} 构造器引用与基本类型自动回退）。
 */
@FunctionalInterface
public interface Mapper1<A, V> extends Function<A, V>, Serializable {
    @Override
    V apply(A a);
}
