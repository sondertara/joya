package com.sondertara.joya.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实体字段缓存、无参实例化与反射读写工具。
 *
 * @author huangxiaohu
 */
public final class ClassUtils {

    private static final ClassValue<List<Field>> FIELD_CACHE = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> k) {
            List<Field> fields = new ArrayList<>();
            Class<?> current = k;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    fields.add(field);
                }
                current = current.getSuperclass();
            }
            return Collections.unmodifiableList(fields);
        }
    };

    private ClassUtils() {
    }

    public static boolean isPrimitiveWrapper(Class<?> clazz) {
        return clazz != null &&
                (clazz == Boolean.class || clazz == Byte.class || clazz == Character.class ||
                        clazz == Short.class || clazz == Integer.class || clazz == Long.class ||
                        clazz == Float.class || clazz == Double.class || clazz == Void.class);
    }

    public static List<Field> getFieldsByCache(Class<?> clazz) {
        return FIELD_CACHE.get(clazz);
    }

    public static <T> T newInstance(Class<T> clazz) {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
        }
    }

    public static Object getFieldValue(Object target, String fieldName) {
        if (target == null || Strings.isBlank(fieldName)) {
            return null;
        }
        for (Field field : getFieldsByCache(target.getClass())) {
            if (field.getName().equals(fieldName)) {
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to read field [" + fieldName + "] on " + target.getClass().getName(), e);
                }
            }
        }
        return null;
    }

    public static void setFieldValue(Object target, String fieldName, Object value) {
        if (target == null || Strings.isBlank(fieldName)) {
            return;
        }
        for (Field field : getFieldsByCache(target.getClass())) {
            if (field.getName().equals(fieldName)) {
                try {
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to write field [" + fieldName + "] on " + target.getClass().getName(), e);
                }
            }
        }
    }
}
