package com.sondertara.joya.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 类型工具，替代 common-tara 的 reflect.type.TypeUtils。
 *
 * <p>职责限定在 {@link java.lang.reflect.Type} 的泛型解析与类型判定；
 * 反射操作（字段遍历、注解、实例化）归 {@link ClassUtils} / {@link PropertyUtils}。
 *
 * @author huangxiaohu
 */
public final class TypeUtils {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = new HashMap<>(9);
    private static final Map<Class<?>, Class<?>> WRAPPER_PRIMITIVE_MAP = new HashMap<>(9);

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
        for (Map.Entry<Class<?>, Class<?>> entry : PRIMITIVE_WRAPPER_MAP.entrySet()) {
            WRAPPER_PRIMITIVE_MAP.put(entry.getValue(), entry.getKey());
        }
    }

    private TypeUtils() {
    }

    // ==================== 泛型实参解析 ====================

    /**
     * 获得 Type 对应的原始类。
     * <ul>
     *   <li>{@link Class} → 自身</li>
     *   <li>{@link ParameterizedType} → raw type</li>
     *   <li>{@link GenericArrayType} / 数组 → 数组类</li>
     *   <li>{@link WildcardType} → 上界</li>
     *   <li>{@link TypeVariable} → 上界</li>
     *   <li>其余（含 null）→ {@link Object}</li>
     * </ul>
     */
    public static Class<?> getClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = getClass(((GenericArrayType) type).getGenericComponentType());
            return Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length > 0 ? getClass(upper[0]) : Object.class;
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length > 0 ? getClass(bounds[0]) : Object.class;
        }
        return Object.class;
    }

    /** {@link #getClass(Type)} 的别名，命名与 Apache Commons TypeUtils 对齐。 */
    public static Class<?> getRawType(Type type) {
        return getClass(type);
    }

    /** 取第一个泛型实参；无实参时兜底 {@link Object}。 */
    public static Type getTypeArgument(Type type) {
        return getTypeArgument(type, 0);
    }

    /** 取第 index 个泛型实参；越界或非参数化类型时兜底 {@link Object}。 */
    public static Type getTypeArgument(Type type, int index) {
        Type[] arguments = getTypeArguments(type);
        if (arguments != null && arguments.length > index) {
            return arguments[index];
        }
        return Object.class;
    }

    /** 取全部泛型实参；仅 {@link ParameterizedType} 有意义，其余返回 null。 */
    public static Type[] getTypeArguments(Type type) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments();
        }
        return null;
    }

    // ==================== 方法 / 字段泛型 ====================

    /** 方法返回类型的完整泛型信息（含实参，如 {@code List<User>}）。 */
    public static Type getReturnType(Method method) {
        return method.getGenericReturnType();
    }

    /** 方法返回类型的原始类。 */
    public static Class<?> getReturnClass(Method method) {
        return getClass(method.getGenericReturnType());
    }

    /** 方法参数类型的完整泛型信息。 */
    public static Type[] getParamTypes(Method method) {
        return method.getGenericParameterTypes();
    }

    /** 第 index 个参数的类型；越界时兜底 {@link Object}。 */
    public static Type getParamType(Method method, int index) {
        Type[] types = method.getGenericParameterTypes();
        return index >= 0 && index < types.length ? types[index] : Object.class;
    }

    /** 第 index 个参数的原始类。 */
    public static Class<?> getParamClass(Method method, int index) {
        return getClass(getParamType(method, index));
    }

    /** 第一个参数的原始类；无参数时兜底 {@link Object}。 */
    public static Class<?> getFirstParamClass(Method method) {
        return getParamClass(method, 0);
    }

    /** 字段声明类型的完整泛型信息。 */
    public static Type getFieldType(Field field) {
        return field.getGenericType();
    }

    // ==================== 泛型基类解析 ====================

    /**
     * 解析 clazz 继承链上 genericIfc 的第一个泛型实参。
     * <p>例：{@code class UserDao extends BaseDao<User, Long>}，
     * {@code resolveTypeArgument(UserDao.class, BaseDao.class)} → {@code User}。
     * 支持链式继承（{@code class UserDao extends BaseUserDao<User>}，
     * {@code class BaseUserDao<U> extends BaseDao<U>}）。
     * 无法解析时返回 null。
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
        if (generic instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
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
        if (genericType instanceof ParameterizedType && rawType != null) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] arguments = parameterizedType.getActualTypeArguments();
            TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
            for (int i = 0; i < typeParameters.length && i < arguments.length; i++) {
                Type argument = arguments[i];
                if (argument instanceof TypeVariable<?>) {
                    // 上层可能已绑定过该变量（链式继承），优先取已解析值
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
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof TypeVariable<?>) {
            Type resolved = variableMap.get(type);
            if (resolved != null) {
                return resolveClass(resolved, variableMap);
            }
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length > 0 ? resolveClass(bounds[0], variableMap) : Object.class;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = resolveClass(((GenericArrayType) type).getGenericComponentType(), variableMap);
            return Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length > 0 ? resolveClass(upper[0], variableMap) : Object.class;
        }
        return Object.class;
    }

    // ==================== 基础类型判定 ====================

    /** 是否为 Java 原始类型（int、boolean 等）。 */
    public static boolean isPrimitive(Class<?> clazz) {
        return clazz != null && clazz.isPrimitive();
    }

    /** 是否为可直接映射/绑定的基础类型：原始类型、包装类型、String、Number、日期时间、UUID、枚举、byte[] 等。 */
    public static boolean isBasicType(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        return clazz.isPrimitive()
                || ClassUtils.isPrimitiveWrapper(clazz)
                || clazz == String.class
                || clazz == BigDecimal.class
                || clazz == BigInteger.class
                || Number.class.isAssignableFrom(clazz)
                || clazz == Date.class
                || clazz == java.sql.Date.class
                || clazz == java.sql.Timestamp.class
                || clazz == LocalDate.class
                || clazz == LocalDateTime.class
                || clazz == LocalTime.class
                || clazz == Instant.class
                || clazz == Duration.class
                || clazz == Period.class
                || clazz == UUID.class
                || clazz == byte[].class
                || clazz == char[].class
                || clazz.isEnum();
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

    /** 包装类型 → 原始类型；已是非包装类型时原样返回。 */
    public static Class<?> getUnwrapClass(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        Class<?> primitive = WRAPPER_PRIMITIVE_MAP.get(clazz);
        return primitive != null ? primitive : clazz;
    }

    /** 是否为数组类型（含泛型数组 {@code T[]}）。 */
    public static boolean isArrayType(Type type) {
        return type instanceof GenericArrayType
                || (type instanceof Class && ((Class<?>) type).isArray());
    }

    /** 数组的组件类型；非数组时返回 null。 */
    public static Type getArrayComponentType(Type type) {
        if (type instanceof GenericArrayType) {
            return ((GenericArrayType) type).getGenericComponentType();
        }
        if (type instanceof Class && ((Class<?>) type).isArray()) {
            return ((Class<?>) type).getComponentType();
        }
        return null;
    }

    /** 集合元素类型：{@code List<User>} → User，{@code User[]} → User；无法解析时兜底 {@link Object}。 */
    public static Class<?> getCollectionElementType(Type type) {
        if (isArrayType(type)) {
            return getClass(getArrayComponentType(type));
        }
        return getClass(getTypeArgument(type, 0));
    }

    /** Map 的 Key/Value 类型：{@code Map<String, User>} → [String, User]；无法解析时兜底 {@link Object}。 */
    public static Class<?>[] getMapKeyAndValueTypes(Type type) {
        Type[] arguments = getTypeArguments(type);
        if (arguments != null && arguments.length >= 2) {
            return new Class<?>[] { getClass(arguments[0]), getClass(arguments[1]) };
        }
        return new Class<?>[] { Object.class, Object.class };
    }

    // ==================== 其他实用 ====================

    /** 原始类型的零值（0 / false / '\0'）；非原始类型返回 null。 */
    public static Object getDefaultValue(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        if (clazz == boolean.class) {
            return Boolean.FALSE;
        }
        if (clazz == byte.class) {
            return (byte) 0;
        }
        if (clazz == short.class) {
            return (short) 0;
        }
        if (clazz == int.class) {
            return 0;
        }
        if (clazz == long.class) {
            return 0L;
        }
        if (clazz == float.class) {
            return 0F;
        }
        if (clazz == double.class) {
            return 0D;
        }
        if (clazz == char.class) {
            return '\0';
        }
        return null;
    }

    /** 是否含未绑定的类型变量（{@code List<T>}、{@code T[]}、{@code ? extends T}）。 */
    public static boolean hasTypeVariable(Type type) {
        if (type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            for (Type argument : ((ParameterizedType) type).getActualTypeArguments()) {
                if (hasTypeVariable(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return hasTypeVariable(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            for (Type bound : ((WildcardType) type).getUpperBounds()) {
                if (hasTypeVariable(bound)) {
                    return true;
                }
            }
            for (Type bound : ((WildcardType) type).getLowerBounds()) {
                if (hasTypeVariable(bound)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否为未知类型（null 或 {@link Object}）。 */
    public static boolean isUnknown(Type type) {
        return type == null || type == Object.class;
    }

    /** 对象实例是否匹配该类型；null 视为匹配（与 Apache Commons 语义一致）。 */
    public static boolean isInstance(Type type, Object value) {
        if (value == null) {
            return true;
        }
        if (type instanceof Class) {
            return ((Class<?>) type).isInstance(value);
        }
        if (type instanceof ParameterizedType) {
            return ((Class<?>) ((ParameterizedType) type).getRawType()).isInstance(value);
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = getClass(((GenericArrayType) type).getGenericComponentType());
            return value.getClass().isArray()
                    && component.isAssignableFrom(value.getClass().getComponentType());
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length > 0 && isInstance(upper[0], value);
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length > 0 && isInstance(bounds[0], value);
        }
        return true;
    }

    /**
     * 运行时构造参数化类型，替代 {@code new TypeReference<List<User>>(){}} 的匿名类写法。
     * 例：{@code parameterize(List.class, User.class)}。
     *
     * @throws IllegalArgumentException 实参个数与 rawType 的类型参数个数不符
     */
    public static ParameterizedType parameterize(Class<?> rawType, Type... typeArguments) {
        Objects.requireNonNull(rawType, "rawType must not be null");
        TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
        if (typeParameters.length != typeArguments.length) {
            throw new IllegalArgumentException("Mismatch of generic type arguments: " + rawType.getName()
                    + " expects " + typeParameters.length + ", got " + typeArguments.length);
        }
        return new ParameterizedTypeImpl(rawType, typeArguments);
    }

    /** Type 的可读字符串表示，用于日志与调试。 */
    public static String typeToString(Type type) {
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                return typeToString(clazz.getComponentType()) + "[]";
            }
            return clazz.getName();
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            StringBuilder builder = new StringBuilder();
            if (parameterizedType.getOwnerType() != null) {
                builder.append(typeToString(parameterizedType.getOwnerType())).append('.');
            }
            builder.append(((Class<?>) parameterizedType.getRawType()).getName()).append('<');
            Type[] arguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(typeToString(arguments[i]));
            }
            return builder.append('>').toString();
        }
        if (type instanceof GenericArrayType) {
            return typeToString(((GenericArrayType) type).getGenericComponentType()) + "[]";
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            if (wildcardType.getLowerBounds().length > 0) {
                return "? super " + typeToString(wildcardType.getLowerBounds()[0]);
            }
            if (wildcardType.getUpperBounds().length > 0 && wildcardType.getUpperBounds()[0] != Object.class) {
                return "? extends " + typeToString(wildcardType.getUpperBounds()[0]);
            }
            return "?";
        }
        if (type instanceof TypeVariable) {
            return ((TypeVariable<?>) type).getName();
        }
        return String.valueOf(type);
    }

    private static final class ParameterizedTypeImpl implements ParameterizedType {

        private final Class<?> rawType;
        private final Type[] typeArguments;

        private ParameterizedTypeImpl(Class<?> rawType, Type[] typeArguments) {
            this.rawType = rawType;
            this.typeArguments = typeArguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return typeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType other = (ParameterizedType) obj;
            return Objects.equals(getOwnerType(), other.getOwnerType())
                    && Objects.equals(getRawType(), other.getRawType())
                    && Arrays.equals(getActualTypeArguments(), other.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(getActualTypeArguments()) ^ Objects.hashCode(getRawType());
        }

        @Override
        public String toString() {
            return typeToString(this);
        }
    }
}
