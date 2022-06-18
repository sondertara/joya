package com.sondertara.joya.utils.cache;

import java.util.Optional;

/**
 * 本地缓存接口
 *
 * @param <K> Key的类型
 * @param <V> Value的类型
 * @author huangxiaohu
 */
public interface ILocalCache<K, V> {

  /**
   * 从缓存中获取数据
   *
   * @param key key
   * @return value
   */
  Optional<V> get(K key);

  /**
   * 从缓存中获取数据
   *
   * @param key key
   */
  void remove(K key);
}
