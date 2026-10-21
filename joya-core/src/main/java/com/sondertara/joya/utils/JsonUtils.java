package com.sondertara.joya.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON 工具，替代 common-tara 的 JsonUtils。
 *
 * @author huangxiaohu
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private JsonUtils() {
    }

    public static String toString(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    public static String toJsonString(Object obj) {
        return toString(obj);
    }

    @SuppressWarnings("unchecked")
    public static <T> T toJavaObject(String json, java.lang.reflect.Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return (T) MAPPER.readValue(json,
                    com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance().constructType(type));
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    public static <T> T toObject(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    public static ObjectMapper getMapper() {
        return MAPPER;
    }
}
