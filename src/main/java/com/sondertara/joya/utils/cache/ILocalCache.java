package com.sondertara.joya.utils.cache;

import java.util.Optional;

/**
 * 本地缓存接口 
 * @author huangxiaohu
 * 
 * @param <K> Key的类型 
 * @param <V> Value的类型 
 */  
public interface ILocalCache <K, V> {  
      
    /** 
     * 从缓存中获取数据 
     * @param key 
     * @return value 
     */  
    public Optional<V> get(K key);

    /**
     * 从缓存中获取数据
     * @param key
     * @return value
     */
    public void remove(K key);
}  