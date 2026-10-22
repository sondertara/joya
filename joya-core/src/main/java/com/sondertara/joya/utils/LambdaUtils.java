package com.sondertara.joya.utils;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.exceptions.TaraException;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Lambda 方法引用元数据解析与 {@link ClassValue} 强缓存工具：
 * <ul>
 *   <li>使用 JDK {@link ClassValue} 将解析出的不可变 {@link LambdaMeta} 直接关联在 Lambda 合成 {@link Class} 上；</li>
 *   <li>避免 {@code WeakReference<SerializedLambda>} 在每次 Young GC 后被清空导致的重复反射开销；</li>
 *   <li>同时在首次解析时一次性计算好 {@code receiverClass}、{@code returnType} 与 {@code propertyName}，后续查询调用均为 O(1) 字段直读。</li>
 * </ul>
 */
public final class LambdaUtils {

    private record LambdaMeta(
            SerializedLambda serializedLambda,
            Class<?> receiverClass,
            Class<?> returnType,
            String propertyName
    ) {
    }

    private static final class Holder {
        volatile LambdaMeta meta;
    }

    private static final ClassValue<Holder> LAMBDA_CACHE = new ClassValue<>() {
        @Override
        protected Holder computeValue(Class<?> type) {
            return new Holder();
        }
    };

    private LambdaUtils() {
    }

    /**
     * 解析 Lambda 表达式获取 {@link SerializedLambda}。
     */
    public static <T> SerializedLambda resolve(IGetter<T, ?> function) {
        return resolveMeta(function).serializedLambda();
    }

    /**
     * 获取方法引用的底层方法名（如 {@code getUserName}）。
     */
    public static <T> String getImplMethodName(IGetter<T, ?> function) {
        return resolveMeta(function).serializedLambda().getImplMethodName();
    }

    /**
     * 解析方法引用的接收者类型：{@code User::getId} 返回 {@code User.class}。
     * <p>优先使用 {@code instantiatedMethodType}，即使 getter 声明在父类也返回子类接收者类型。
     */
    public static Class<?> getReceiverClass(IGetter<?, ?> function) {
        return resolveMeta(function).receiverClass();
    }

    /**
     * 解析方法引用的返回值类型（基本类型自动装箱）：{@code User::getAge} 返回 {@code Integer.class}。
     */
    public static Class<?> getReturnType(IGetter<?, ?> function) {
        return resolveMeta(function).returnType();
    }

    /**
     * 解析方法引用对应的属性名：{@code getUserName} → {@code userName}，{@code isActive} → {@code active}，
     * record 访问器 {@code name} → {@code name}。
     */
    public static String getPropertyName(IGetter<?, ?> function) {
        return resolveMeta(function).propertyName();
    }

    private static LambdaMeta resolveMeta(IGetter<?, ?> function) {
        Objects.requireNonNull(function, "Lambda function must not be null");
        Holder holder = LAMBDA_CACHE.get(function.getClass());
        LambdaMeta meta = holder.meta;
        if (meta == null) {
            meta = parseMeta(function);
            holder.meta = meta;
        }
        return meta;
    }

    private static LambdaMeta parseMeta(IGetter<?, ?> function) {
        SerializedLambda lambda = extractSerializedLambda(function);
        Class<?> receiverClass = parseReceiverClass(lambda);
        Class<?> returnType = parseReturnType(lambda);
        String propertyName = parsePropertyName(lambda.getImplMethodName());
        return new LambdaMeta(lambda, receiverClass, returnType, propertyName);
    }

    private static SerializedLambda extractSerializedLambda(IGetter<?, ?> function) {
        try {
            Method writeMethod = function.getClass().getDeclaredMethod("writeReplace");
            writeMethod.setAccessible(true);
            SerializedLambda serializedLambda = (SerializedLambda) writeMethod.invoke(function);
            if (serializedLambda == null) {
                throw new TaraException("Unable to parse lambda: " + function);
            }
            return serializedLambda;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new TaraException("Failed to extract SerializedLambda from " + function, e);
        }
    }

    private static Class<?> parseReceiverClass(SerializedLambda lambda) {
        String type = lambda.getInstantiatedMethodType();
        if (type != null && type.startsWith("(L")) {
            int end = type.indexOf(';');
            if (end > 2) {
                return loadClass(type.substring(2, end).replace('/', '.'));
            }
        }
        return loadClass(lambda.getImplClass().replace('/', '.'));
    }

    private static Class<?> parseReturnType(SerializedLambda lambda) {
        String type = lambda.getInstantiatedMethodType();
        if (type == null || type.indexOf(')') < 0) {
            type = lambda.getImplMethodSignature();
        }
        return boxed(descriptorToClass(type.substring(type.indexOf(')') + 1)));
    }

    private static String parsePropertyName(String method) {
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
            return Strings.lowerFirst(method.substring(3));
        }
        if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2))) {
            return Strings.lowerFirst(method.substring(2));
        }
        return method;
    }

    /** 基本类型装箱；非基本类型原样返回。 */
    public static Class<?> boxed(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static Class<?> descriptorToClass(String descriptor) {
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
            case 'L' -> loadClass(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            case '[' -> loadClass(descriptor.replace('/', '.'));
            default -> throw new TaraException("Unsupported type descriptor: " + descriptor);
        };
    }

    /** 按线程上下文类加载器优先加载类。 */
    public static Class<?> loadClass(String className) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // 回退到当前类加载器
            }
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new TaraException(e);
        }
    }
}
