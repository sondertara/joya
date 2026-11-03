package com.sondertara.joya.utils;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashMap;
import java.util.Map;

/**
 * 类型工具，负责 {@link Type} 的泛型基类实参解析、原始/包装类型转换与数值类型判定。
 *
 * @author huangxiaohu
 */
public final class TypeUtils {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = new HashMap<>(9);

    static {
        PRIMITIVE_WRAPPER_MAP.put(boolean.class, Boolean.class);
        PRIMITIVE_WRAPPER_MAP.put(byte.class, Byte.class);
        PRIMITIVE_WRAPPER_MAP.put(short.class, Short.class);
        PRIMITIVE_WRAPPER_MAP.put(char.class, Character.class);
        PRIMITIVE_WRAPPER_MAP.put(int.class, Integer.class);
        PRIMITIVE_WRAPPER_MAP.put(long.class, Long.class);
        PRIMITIVE_WRAPPER_MAP.put(float.class, Float.class);
        PRIMITIVE_WRAPPER_MAP.put(double.class, Double.class);
        PRIMITIVE_WRAPPER_MAP.put(void.class, Void.class);
    }

    private TypeUtils() {
    }

    /**
     * 解析 clazz 继承链上 genericIfc 的第一个泛型实参。
     * <p>支持链式继承（如 {@code class UserDao extends BaseUserDao<User>}，
     * {@code class BaseUserDao<U> extends BaseDao<U>}）。无法解析时返回 null。
     */
    public static Class<?> resolveTypeArgument(Class<?> clazz, Class<?> genericIfc) {
        Class<?>[] typeArguments = resolveTypeArguments(clazz, genericIfc);
        return typeArguments.length == 0 ? null : typeArguments[0];
    }

    /** {@link #resolveTypeArgument(Class, Class)} 的多实参版本；无法解析时返回空数组。 */
    public static Class<?>[] resolveTypeArguments(Class<?> clazz, Class<?> genericIfc) {
        if (clazz == null || genericIfc == null || !genericIfc.isAssignableFrom(clazz)) {
            return new Class<?>[0];
        }
        Map<TypeVariable<?>, Type> variableMap = new HashMap<>();
        collectTypeVariables(clazz, variableMap);
        Type generic = findGenericType(clazz, genericIfc);
        if (generic instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Class<?>[] result = new Class<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                result[i] = resolveClass(arguments[i], variableMap);
            }
            return result;
        }
        return new Class<?>[0];
    }

    /** 自底向上收集继承链（含父类与多层父接口）上的类型变量绑定，处理链式继承（T → U → User）。 */
    private static void collectTypeVariables(Class<?> clazz, Map<TypeVariable<?>, Type> variableMap) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        collectFromType(clazz.getGenericSuperclass(), clazz.getSuperclass(), variableMap);
        Type[] genericInterfaces = clazz.getGenericInterfaces();
        Class<?>[] interfaces = clazz.getInterfaces();
        for (int i = 0; i < genericInterfaces.length && i < interfaces.length; i++) {
            collectFromType(genericInterfaces[i], interfaces[i], variableMap);
            collectTypeVariables(interfaces[i], variableMap);
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            collectTypeVariables(superClass, variableMap);
        }
    }

    private static void collectFromType(Type genericType, Class<?> rawType, Map<TypeVariable<?>, Type> variableMap) {
        if (genericType instanceof ParameterizedType parameterizedType && rawType != null) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
            for (int i = 0; i < typeParameters.length && i < arguments.length; i++) {
                Type argument = arguments[i];
                if (argument instanceof TypeVariable<?>) {
                    Type resolved = variableMap.get(argument);
                    variableMap.putIfAbsent(typeParameters[i], resolved != null ? resolved : argument);
                } else {
                    variableMap.putIfAbsent(typeParameters[i], argument);
                }
            }
        }
    }

    /** 在 clazz 继承链（含父类与多层父接口）上定位 genericIfc 对应的 Type（尽量为 ParameterizedType）。 */
    private static Type findGenericType(Class<?> clazz, Class<?> genericIfc) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }
        Type genericSuper = clazz.getGenericSuperclass();
        if (genericSuper instanceof ParameterizedType pt && pt.getRawType() == genericIfc) {
            return genericSuper;
        } else if (genericSuper == genericIfc) {
            return genericSuper;
        }
        for (Type genericInterface : clazz.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt && pt.getRawType() == genericIfc) {
                return genericInterface;
            } else if (genericInterface == genericIfc) {
                return genericInterface;
            }
        }
        for (Class<?> ifc : clazz.getInterfaces()) {
            Type found = findGenericType(ifc, genericIfc);
            if (found != null) {
                return found;
            }
        }
        return findGenericType(clazz.getSuperclass(), genericIfc);
    }

    /** 将 Type 解析为 Class，类型变量经 variableMap 替换；未绑定的变量取上界。 */
    private static Class<?> resolveClass(Type type, Map<TypeVariable<?>, Type> variableMap) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof TypeVariable<?> tv) {
            Type resolved = variableMap.get(tv);
            if (resolved != null) {
                return resolveClass(resolved, variableMap);
            }
            Type[] bounds = tv.getBounds();
            return bounds.length > 0 ? resolveClass(bounds[0], variableMap) : Object.class;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        if (type instanceof GenericArrayType gat) {
            Class<?> component = resolveClass(gat.getGenericComponentType(), variableMap);
            return Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            return upper.length > 0 ? resolveClass(upper[0], variableMap) : Object.class;
        }
        return Object.class;
    }

    /** 是否为数值类型（Number 子类或原始数值类型）。 */
    public static boolean isNumber(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return Number.class.isAssignableFrom(clazz)
                || clazz == byte.class || clazz == short.class || clazz == int.class
                || clazz == long.class || clazz == float.class || clazz == double.class;
    }

    /** 原始类型 → 包装类型；已是非原始类型时原样返回。 */
    public static Class<?> getWrapClass(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        Class<?> wrapper = PRIMITIVE_WRAPPER_MAP.get(clazz);
        return wrapper != null ? wrapper : clazz;
    }

    /** 是否为原始类型、数值类型或原始包装类型（单值映射判断）。 */
    public static boolean isPrimitiveOrNumber(Class<?> propertyType) {
        return propertyType != null
                && (propertyType.isPrimitive()
                || isNumber(propertyType)
                || ClassUtils.isPrimitiveWrapper(propertyType));
    }
}
