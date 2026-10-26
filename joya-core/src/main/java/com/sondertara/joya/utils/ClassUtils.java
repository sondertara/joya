package com.sondertara.joya.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 反射工具，补充 ReflectUtils 缺失功能。
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

    public static String getPackageName(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        return pkg == null ? "" : pkg.getName();
    }

    public static List<Field> getFieldsByCache(Class<?> clazz) {
        return FIELD_CACHE.get(clazz);
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

    public static void copyProperties(Object source, Object target) {
        if (source == null || target == null) {
            return;
        }
        List<Field> sourceFields = getFieldsByCache(source.getClass());
        List<Field> targetFields = getFieldsByCache(target.getClass());
        for (Field targetField : targetFields) {
            if (Modifier.isFinal(targetField.getModifiers())) {
                continue;
            }
            for (Field sourceField : sourceFields) {
                if (sourceField.getName().equals(targetField.getName())) {
                    try {
                        sourceField.setAccessible(true);
                        targetField.setAccessible(true);
                        Object val = sourceField.get(source);
                        if (val != null) {
                            if (TypeUtils.getWrapClass(targetField.getType()).isInstance(val)) {
                                targetField.set(target, val);
                            } else {
                                targetField.set(target, ConvertUtils.convert(val, targetField.getType()));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    break;
                }
            }
        }
    }
}
