package com.sondertara.joya.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Set 工具，替代 common-tara 的 collection.Sets。
 *
 * @author huangxiaohu
 */
public final class Sets {

    private Sets() {
    }

    public static <E> Set<E> newHashSet() {
        return new HashSet<>();
    }

    @SafeVarargs
    public static <E> Set<E> newHashSet(E... elements) {
        HashSet<E> set = new HashSet<>();
        Collections.addAll(set, elements);
        return set;
    }

    public static <E> Set<E> newHashSet(int initialCapacity) {
        return new HashSet<>(initialCapacity);
    }

    public static <E> Set<E> newLinkedHashSet() {
        return new LinkedHashSet<>();
    }

    @SafeVarargs
    public static <E> Set<E> newLinkedHashSet(E... elements) {
        LinkedHashSet<E> set = new LinkedHashSet<>();
        Collections.addAll(set, elements);
        return set;
    }
}
