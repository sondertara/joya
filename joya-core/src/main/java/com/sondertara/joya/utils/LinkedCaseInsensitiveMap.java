package com.sondertara.joya.utils;

import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 保留插入顺序且 Key 大小写不敏感的 Map 实现（用于 JDBC 列名与属性名映射）。
 *
 * @param <V> 值类型
 */
public class LinkedCaseInsensitiveMap<V> extends AbstractMap<String, V> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final LinkedHashMap<String, V> targetMap;
    private final HashMap<String, String> caseInsensitiveKeys;
    private final Locale locale;

    public LinkedCaseInsensitiveMap() {
        this(16, Locale.ROOT);
    }

    public LinkedCaseInsensitiveMap(int expectedSize) {
        this(expectedSize, Locale.ROOT);
    }

    public LinkedCaseInsensitiveMap(int expectedSize, Locale locale) {
        this.targetMap = new LinkedHashMap<>(expectedSize);
        this.caseInsensitiveKeys = new HashMap<>(expectedSize);
        this.locale = locale == null ? Locale.ROOT : locale;
    }

    private String convertKey(String key) {
        return key.toLowerCase(this.locale);
    }

    @Override
    public int size() {
        return this.targetMap.size();
    }

    @Override
    public boolean isEmpty() {
        return this.targetMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String s && this.caseInsensitiveKeys.containsKey(convertKey(s));
    }

    @Override
    public boolean containsValue(Object value) {
        return this.targetMap.containsValue(value);
    }

    @Override
    public V get(Object key) {
        if (key instanceof String s) {
            String realKey = this.caseInsensitiveKeys.get(convertKey(s));
            if (realKey != null) {
                return this.targetMap.get(realKey);
            }
        }
        return null;
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        if (key instanceof String s) {
            String realKey = this.caseInsensitiveKeys.get(convertKey(s));
            if (realKey != null) {
                return this.targetMap.get(realKey);
            }
        }
        return defaultValue;
    }

    @Override
    public V put(String key, V value) {
        String oldKey = this.caseInsensitiveKeys.put(convertKey(key), key);
        V oldKeyValue = null;
        if (oldKey != null && !oldKey.equals(key)) {
            oldKeyValue = this.targetMap.remove(oldKey);
        }
        V oldValue = this.targetMap.put(key, value);
        return oldKeyValue != null ? oldKeyValue : oldValue;
    }

    @Override
    public V putIfAbsent(String key, V value) {
        String oldKey = this.caseInsensitiveKeys.get(convertKey(key));
        if (oldKey != null) {
            V existing = this.targetMap.get(oldKey);
            if (existing != null) {
                return existing;
            }
        }
        return put(key, value);
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        map.forEach(this::put);
    }

    @Override
    public V remove(Object key) {
        if (key instanceof String s) {
            String realKey = this.caseInsensitiveKeys.remove(convertKey(s));
            if (realKey != null) {
                return this.targetMap.remove(realKey);
            }
        }
        return null;
    }

    @Override
    public void clear() {
        this.caseInsensitiveKeys.clear();
        this.targetMap.clear();
    }

    @Override
    public Set<String> keySet() {
        return this.targetMap.keySet();
    }

    @Override
    public Collection<V> values() {
        return this.targetMap.values();
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        return this.targetMap.entrySet();
    }
}
