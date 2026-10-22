package com.sondertara.joya.utils;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 不可变键值二元组。
 *
 * @param <K> 左值（Key）类型
 * @param <V> 右值（Value）类型
 */
public class Pair<K, V> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final K key;
    private final V value;

    public Pair(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public static <K, V> Pair<K, V> of(K key, V value) {
        return new Pair<>(key, value);
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public K left() {
        return key;
    }

    public V right() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair<?, ?> that)) {
            return false;
        }
        return Objects.equals(key, that.key) && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }

    @Override
    public String toString() {
        return "Pair{key=" + key + ", value=" + value + '}';
    }
}
