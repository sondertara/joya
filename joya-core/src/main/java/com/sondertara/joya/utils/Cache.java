package com.sondertara.joya.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 简单本地缓存，支持基于单调时钟（{@link System#nanoTime()}）的过期时间控制。
 *
 * @author huangxiaohu
 */
public class Cache<K, V> {

    private final long expireAfterWriteNanos;
    private final Map<K, Value<V>> cache = new ConcurrentHashMap<>();

    Cache(long expireAfterWriteMillis) {
        this.expireAfterWriteNanos = expireAfterWriteMillis > 0
                ? TimeUnit.MILLISECONDS.toNanos(expireAfterWriteMillis)
                : 0L;
    }

    public V get(K key) {
        if (key == null) {
            return null;
        }
        Value<V> value = cache.get(key);
        if (value == null) {
            return null;
        }
        if (expireAfterWriteNanos > 0 && System.nanoTime() - value.createdNanos > expireAfterWriteNanos) {
            cache.remove(key, value);
            return null;
        }
        return value.value;
    }

    public V get(K key, Supplier<V> loader) {
        V value = get(key);
        if (value == null) {
            value = loader.get();
            if (value != null) {
                put(key, value);
            }
        }
        return value;
    }

    public void put(K key, V value) {
        if (key != null && value != null) {
            cache.put(key, new Value<>(value));
        }
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

    private static final class Value<V> {
        final V value;
        final long createdNanos;

        Value(V value) {
            this.value = value;
            this.createdNanos = System.nanoTime();
        }
    }
}
