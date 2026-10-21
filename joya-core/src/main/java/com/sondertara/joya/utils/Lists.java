package com.sondertara.joya.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Collection 工具，替代 common-tara 的 collection.Lists / Sets。
 *
 * @author huangxiaohu
 */
public final class Lists {

    private Lists() {
    }

    public static <T> List<T> newArrayList() {
        return new ArrayList<>();
    }

    public static <T> List<T> newArrayList(int initialCapacity) {
        return new ArrayList<>(initialCapacity);
    }

    public static <T> List<T> newArrayList(Collection<? extends T> elements) {
        return new ArrayList<>(elements);
    }

    @SafeVarargs
    public static <T> List<T> newArrayList(T... elements) {
        ArrayList<T> list = new ArrayList<>(elements.length);
        Collections.addAll(list, elements);
        return list;
    }

    public static <T> Set<T> newHashSet() {
        return new HashSet<>();
    }

    @SafeVarargs
    public static <T> Set<T> newHashSet(T... elements) {
        HashSet<T> set = new HashSet<>();
        Collections.addAll(set, elements);
        return set;
    }

    public static <T> Set<T> newLinkedHashSet() {
        return new LinkedHashSet<>();
    }

    public static <T> Set<T> newLinkedHashSet(Collection<? extends T> elements) {
        return new LinkedHashSet<>(elements);
    }

    @SafeVarargs
    public static <T> Set<T> newLinkedHashSet(T... elements) {
        LinkedHashSet<T> set = new LinkedHashSet<>();
        Collections.addAll(set, elements);
        return set;
    }

    public static <T> List<List<T>> partition(List<T> list, int size) {
        if (list == null || list.isEmpty() || size <= 0) {
            return Collections.emptyList();
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            int end = Math.min(i + size, list.size());
            result.add(new ArrayList<>(list.subList(i, end)));
        }
        return result;
    }
}