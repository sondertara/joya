package com.sondertara.joya.utils.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 抽象Guava缓存类、缓存模板。 子类需要实现fetchData(key)，从数据库或其他数据源（如Redis）中获取数据。
 * 子类调用getValue(key)方法，从缓存中获取数据，并处理不同的异常，比如value为null时的InvalidCacheLoadException异常。
 *
 * @param <K> key 类型
 * @param <V> value 类型
 * @author huangxiaohu
 * @date 2021-07-09
 */
@Slf4j
public abstract class GuavaAbstractLoadingCache<K, V> {

  /** time unit of second */
  private final TimeUnit timeUnit = TimeUnit.SECONDS;
  /** 最大缓存条数，子类在构造方法中调用setMaximumSize(int size)来更改 */
  private int maximumSize = 1000;
  /** 数据存在时长，子类在构造方法中调用setExpireAfterWriteDuration(int duration)来更改 */
  private int expireAfterWriteDuration = 60;
  /** Cache初始化或被重置的时间 */
  private Date resetTime;
  /** 历史最高记录数 */
  private long highestSize = 0;
  /** 创造历史记录的时间 */
  private Date highestTime;

  private volatile LoadingCache<K, V> cache;

  /**
   * 通过调用getCache().get(key)来获取数据
   *
   * @return cache
   */
  private LoadingCache<K, V> getCache() {
    // 使用双重校验锁保证只有一个cache实例
    if (cache == null) {
      synchronized (this) {
        if (cache == null) {
          cache =
              CacheBuilder.newBuilder()
                  .maximumSize(maximumSize)
                  .expireAfterWrite(expireAfterWriteDuration, timeUnit)
                  .recordStats()
                  .build(
                      new CacheLoader<K, V>() {
                        @Override
                        public V load(@Nonnull K key) {
                          return fetchData(key);
                        }
                      });
          this.resetTime = new Date();
          this.highestTime = new Date();
          log.debug("本地缓存{}初始化成功", this.getClass().getSimpleName());
        }
      }
    }

    return cache;
  }

  public void put(K key, V value) {
    getCache().put(key, value);
  }

  /**
   * 根据key从数据库或其他数据源中获取一个value，并被自动保存到缓存中。
   *
   * @param key key
   * @return value, 连同key一起被加载到缓存中的。
   */
  protected abstract V fetchData(K key);

  /**
   * 从缓存中获取数据（第一次自动调用fetchData从外部获取数据），并处理异常
   *
   * @param key key
   * @return Value
   * @throws ExecutionException e
   */
  protected V getValue(K key) throws ExecutionException {
    V result = getCache().get(key);
    if (getCache().size() > highestSize) {
      highestSize = getCache().size();
      highestTime = new Date();
    }
    return result;
  }

  public void invalidateAll() {
    getCache().invalidateAll();
  }

  public void invalidate(K key) {
    getCache().invalidate(key);
  }

  public long getHighestSize() {
    return highestSize;
  }

  public Date getHighestTime() {
    return highestTime;
  }

  public Date getResetTime() {
    return resetTime;
  }

  public int getMaximumSize() {
    return maximumSize;
  }

  /**
   * 设置最大缓存条数
   *
   * @param maximumSize max cache size
   */
  public void setMaximumSize(int maximumSize) {
    this.maximumSize = maximumSize;
  }

  public int getExpireAfterWriteDuration() {
    return expireAfterWriteDuration;
  }

  /**
   * 设置数据存在时长（second）
   *
   * @param expireAfterWriteDuration e
   */
  public void setExpireAfterWriteDuration(int expireAfterWriteDuration) {
    this.expireAfterWriteDuration = expireAfterWriteDuration;
  }
}
