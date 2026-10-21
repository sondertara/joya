package com.sondertara.joya.utils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Map 工具，替代 common-tara 的 collection.Maps。
 *
 * @author huangxiaohu
 */
public final class Maps {

    private Maps() {
    }

    public static <K, V> Map<K, V> newHashMap() {
        return new HashMap<>();
    }

    public static <K, V> Map<K, V> newHashMap(int initialCapacity) {
        return new HashMap<>(initialCapacity);
    }

    public static <K, V> Map<K, V> newLinkedHashMap() {
        return new LinkedHashMap<>();
    }

    public static <K, V> Map<K, V> newLinkedHashMap(int initialCapacity) {
        return new LinkedHashMap<>(initialCapacity);
    }

    public static <K, V> Map<K, V> newTreeMap() {
        return new TreeMap<>();
    }
}
