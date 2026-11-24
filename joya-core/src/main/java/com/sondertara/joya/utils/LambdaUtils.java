package com.sondertara.joya.utils;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.exceptions.DbException;

import java.beans.Introspector;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Lambda 方法引用元数据解析与 {@link ClassValue} 强缓存工具：
 * <ul>
 *   <li>仅对无捕获（{@code getDeclaredFields().length == 0}）的合成方法引用类按 {@link ClassValue} 缓存 {@link LambdaMeta}，
 *       有状态/捕获型实现每次按实例解析，彻底杜绝跨实例串列污染；</li>
 *   <li>首次解析时一次性计算 {@code implMethodName}、{@code receiverClass}、{@code returnType} 与 {@code propertyName}，
 *       并提供 {@link #resolve(IGetter)} 单次获取完整元数据，避免重复查找。</li>
 * </ul>
 */
public final class LambdaUtils {

    public record LambdaMeta(
            String implMethodName,
            Class<?> receiverClass,
            Class<?> returnType,
            String propertyName
    ) {
    }

    private static final class Holder {
        final boolean stateless;
        volatile LambdaMeta meta;

        Holder(boolean stateless) {
            this.stateless = stateless;
        }
    }

    private static final ClassValue<Holder> LAMBDA_CACHE = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            return new Holder(type.getDeclaredFields().length == 0);
        }
    };

    private LambdaUtils() {
    }

    /**
     * 单次解析并返回方法引用的完整元数据（对无状态方法引用享受 O(1) {@link ClassValue} 缓存）。
     */
    public static LambdaMeta resolve(IGetter<?, ?> function) {
        Objects.requireNonNull(function, "Lambda function must not be null");
        Holder holder = LAMBDA_CACHE.get(function.getClass());
        if (!holder.stateless) {
            return parseMeta(function);
        }
        LambdaMeta meta = holder.meta;
        if (meta == null) {
            meta = parseMeta(function);
            holder.meta = meta;
        }
        return meta;
    }

    /**
     * 获取方法引用的底层方法名（如 {@code getUserName}）。
     */
    public static <T> String getImplMethodName(IGetter<T, ?> function) {
        return resolve(function).implMethodName();
    }

    /**
     * 解析方法引用的接收者类型：{@code User::getId} 返回 {@code User.class}。
     */
    public static Class<?> getReceiverClass(IGetter<?, ?> function) {
        return resolve(function).receiverClass();
    }

    /**
     * 解析方法引用的返回值类型（基本类型自动装箱）：{@code User::getAge} 返回 {@code Integer.class}。
     */
    public static Class<?> getReturnType(IGetter<?, ?> function) {
        return resolve(function).returnType();
    }

    /**
     * 解析方法引用对应的属性名：{@code getUserName} → {@code userName}，{@code isActive} → {@code active}，
     * record 访问器 {@code name} → {@code name}。
     */
    public static String getPropertyName(IGetter<?, ?> function) {
        return resolve(function).propertyName();
    }

    private static LambdaMeta parseMeta(IGetter<?, ?> function) {
        SerializedLambda lambda = extractSerializedLambda(function);
        ClassLoader preferredLoader = function.getClass().getClassLoader();
        String implMethodName = lambda.getImplMethodName();
        Class<?> receiverClass = parseReceiverClass(lambda, preferredLoader);
        Class<?> returnType = parseReturnType(lambda, preferredLoader);
        String propertyName = parsePropertyName(implMethodName);
        return new LambdaMeta(implMethodName, receiverClass, returnType, propertyName);
    }

    private static SerializedLambda extractSerializedLambda(IGetter<?, ?> function) {
        try {
            Method writeMethod = function.getClass().getDeclaredMethod("writeReplace");
            writeMethod.setAccessible(true);
            SerializedLambda serializedLambda = (SerializedLambda) writeMethod.invoke(function);
            if (serializedLambda == null) {
                throw new DbException("Unable to parse lambda: " + function);
            }
            return serializedLambda;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new DbException("Failed to extract SerializedLambda from " + function, e);
        }
    }

    private static Class<?> parseReceiverClass(SerializedLambda lambda, ClassLoader preferredLoader) {
        String type = lambda.getInstantiatedMethodType();
        if (type != null && type.startsWith("(L")) {
            int end = type.indexOf(';');
            if (end > 2) {
                return loadClass(type.substring(2, end).replace('/', '.'), preferredLoader);
            }
        }
        return loadClass(lambda.getImplClass().replace('/', '.'), preferredLoader);
    }

    private static Class<?> parseReturnType(SerializedLambda lambda, ClassLoader preferredLoader) {
        String type = lambda.getInstantiatedMethodType();
        if (type == null || type.indexOf(')') < 0) {
            type = lambda.getImplMethodSignature();
        }
        return boxed(descriptorToClass(type.substring(type.indexOf(')') + 1), preferredLoader));
    }

    private static String parsePropertyName(String method) {
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
            return Introspector.decapitalize(method.substring(3));
        }
        if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2))) {
            return Introspector.decapitalize(method.substring(2));
        }
        return method;
    }

    /** 基本类型装箱；非基本类型原样返回。 */
    public static Class<?> boxed(Class<?> type) {
        return TypeUtils.getWrapClass(type);
    }

    private static Class<?> descriptorToClass(String descriptor, ClassLoader preferredLoader) {
        return switch (descriptor.charAt(0)) {
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'Z' -> boolean.class;
            case 'D' -> double.class;
            case 'F' -> float.class;
            case 'S' -> short.class;
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'V' -> void.class;
            case 'L' -> loadClass(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), preferredLoader);
            case '[' -> loadClass(descriptor.replace('/', '.'), preferredLoader);
            default -> throw new DbException("Unsupported type descriptor: " + descriptor);
        };
    }

    /** 按线程上下文类加载器优先加载类。 */
    public static Class<?> loadClass(String className) {
        return loadClass(className, null);
    }

    /** 按指定类加载器（如 Lambda 所在类加载器）→ 线程上下文类加载器 → 当前类加载器依次加载类。 */
    public static Class<?> loadClass(String className, ClassLoader preferredLoader) {
        if (preferredLoader != null) {
            try {
                return Class.forName(className, false, preferredLoader);
            } catch (ClassNotFoundException ignored) {
                // 回退到线程上下文类加载器
            }
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader != preferredLoader) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // 回退到当前类加载器
            }
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new DbException(e);
        }
    }
}
