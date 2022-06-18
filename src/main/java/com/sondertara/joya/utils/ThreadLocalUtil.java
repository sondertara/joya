package com.sondertara.joya.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * threadLocal
 *
 * @author sondertara
 * @date 2021/11/19 15:23
 * @since 0.0.7
 */
public class ThreadLocalUtil {
  private static final ThreadLocal<Map<String, Object>> THREAD_CONTEXT = new MapThreadLocal();

  private ThreadLocalUtil() {}

  private static Map<String, Object> getContextMap() {
    return THREAD_CONTEXT.get();
  }

  public static Object get(String key) {
    return getContextMap().get(key);
  }

  public static void put(String key, Object value) {
    getContextMap().put(key, value);
  }

  public static void remove(String key) {
    getContextMap().remove(key);
  }

  public static void clear() {
    THREAD_CONTEXT.remove();
  }

  private static class MapThreadLocal extends ThreadLocal<Map<String, Object>> {
    @Override
    protected Map<String, Object> initialValue() {
      return new HashMap<String, Object>(8) {
        @Override
        public Object put(String key, Object value) {
          return super.put(key, value);
        }
      };
    }
  }
}
