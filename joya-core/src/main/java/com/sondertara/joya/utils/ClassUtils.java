package com.sondertara.joya.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具，补充 ReflectUtils 缺失功能。
 *
 * @author huangxiaohu
 */
public final class ClassUtils {

    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private ClassUtils() {
    }

    public static boolean isPrimitiveWrapper(Class<?> clazz) {
        return clazz != null &&
                (clazz == Boolean.class || clazz == Byte.class || clazz == Character.class ||
                        clazz == Short.class || clazz == Integer.class || clazz == Long.class ||
                        clazz == Float.class || clazz == Double.class || clazz == Void.class);
    }

    public static String getPackageName(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        return pkg == null ? "" : pkg.getName();
    }

    public static List<Field> getFieldsByCache(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, k -> {
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
        });
    }

    public static Class<?> getClass(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }

    @SuppressWarnings("unchecked")
    public static <T> T newInstance(Class<T> clazz, Object... args) {
        try {
            if (args == null || args.length == 0) {
                Constructor<T> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            }
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] ctorParamTypes = constructor.getParameterTypes();
                if (ctorParamTypes.length != args.length) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg == null) {
                        if (ctorParamTypes[i].isPrimitive()) {
                            match = false;
                            break;
                        }
                    } else {
                        Class<?> targetType = TypeUtils.getWrapClass(ctorParamTypes[i]);
                        Class<?> actualType = TypeUtils.getWrapClass(arg.getClass());
                        if (!targetType.isAssignableFrom(actualType)) {
                            match = false;
                            break;
                        }
                    }
                }
                if (match) {
                    constructor.setAccessible(true);
                    return (T) constructor.newInstance(args);
                }
            }
            throw new NoSuchMethodException("No matching constructor found for " + clazz.getName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
        }
    }
}
