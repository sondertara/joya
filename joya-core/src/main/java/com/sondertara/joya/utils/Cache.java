package com.sondertara.joya.utils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 简单本地缓存，带过期时间。
 *
 * @author huangxiaohu
 */
public class Cache<K, V> {

    private final long expireAfterWriteMillis;
    private final Map<K, Value<V>> cache = new ConcurrentHashMap<>();

    Cache(long expireAfterWriteMillis) {
        this.expireAfterWriteMillis = expireAfterWriteMillis;
    }

    public V get(K key) {
        Value<V> value = cache.get(key);
        if (value == null) {
            return null;
        }
        if (expireAfterWriteMillis > 0 && System.currentTimeMillis() - value.timestamp > expireAfterWriteMillis) {
            cache.remove(key);
            return null;
        }
        return value.value;
    }

    public V get(K key, Supplier<V> loader) {
        V value = get(key);
        if (value == null) {
            value = loader.get();
            put(key, value);
        }
        return value;
    }

    public void put(K key, V value) {
        cache.put(key, new Value<>(value));
    }

    public void clear() {
        cache.clear();
    }

    public void reset() {
        clear();
    }

    public void destroy() {
        clear();
    }

    public static <K1, V1> Builder<K1, V1> newBuilder() {
        return new Builder<>();
    }

    public static class Builder<K1, V1> {
        private long expireAfterWriteMillis = 0;

        public Builder<K1, V1> expireAfterWrite(long millis) {
            this.expireAfterWriteMillis = millis;
            return this;
        }

        public Cache<K1, V1> build() {
            return new Cache<>(expireAfterWriteMillis);
        }
    }

    private static class Value<V> {
        final V value;
        final long timestamp;

        Value(V value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }
}