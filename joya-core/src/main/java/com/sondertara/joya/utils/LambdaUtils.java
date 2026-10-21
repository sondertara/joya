package com.sondertara.joya.utils;


import com.sondertara.joya.exceptions.TaraException;
import com.sondertara.common.function.IGetter;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.SerializedLambda;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Xiao-Bai
 * @date 2022/8/26 23:58
 * @desc lambda 表达式解析
 */
@Slf4j
public final class LambdaUtils {

    /**
     * FUNCTION 函数的缓存
     */
    private final static Map<String, WeakReference<SerializedLambda>> FUNCTION_CACHE = new ConcurrentHashMap<>();

    /**
     * 解析 Lambda 表达式, 从SFunction中获取序列化的信息
     * @param function 表达式
     * @param <T> 对象类型
     * @return {@link SerializedLambda}
     */
    public static <T> SerializedLambda resolve(IGetter<T, ?> function) {
        Class<?> aClass = function.getClass();
        // 方法引用/ lambda 生成的合成类 getCanonicalName() 返回 null，作为缓存 key 会致 Map.get(null) NPE；
        // getName() 对任意 class 均非 null。
        String cacheKey = aClass.getName();
        return Optional.ofNullable(FUNCTION_CACHE.get(cacheKey))
                .map(WeakReference::get)
                .orElseGet(() -> {
                    SerializedLambda serializedLambda = startParse(function);
                    FUNCTION_CACHE.put(cacheKey, new WeakReference<>(serializedLambda));
                    return serializedLambda;
                });
    }

    /**
     * 解析函数
     */
    private static <T> SerializedLambda startParse(IGetter<T, ?> function) {
        SerializedLambda serializedLambda = null;
        Method writeMethod;
        try {
            // 从function中取出序列化方法
            writeMethod = function.getClass().getDeclaredMethod("writeReplace");
            writeMethod.setAccessible(true);
            serializedLambda = (SerializedLambda) writeMethod.invoke(function);
            writeMethod.setAccessible(false);
        }
        catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        if (Objects.isNull(serializedLambda)) {
            throw new TaraException("Unable to parse：" + function);
        }
        return serializedLambda;
    }

    /**
     * 解析 Lambda 表达式
     * @param function 表达式
     * @param <T> 对象类型
     * @return 对象class类
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> getImplClass(IGetter<T, ?> function) {
        SerializedLambda serializedLambda = resolve(function);
        try {
            return (Class<T>) Class.forName(serializedLambda.getImplClass().replace('/', '.'));
        } catch (ClassNotFoundException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取lambda表达式函数的引用方法名
     * @param function 函数表达式
     * @param <T>
     * @return 方法名称
     */
    public static <T> String getImplMethodName(IGetter<T, ?> function) {
        SerializedLambda serializedLambda = resolve(function);
        return serializedLambda.getImplMethodName();
    }

    /**
     * 获取lambda表达式函数的引用方法返回类型
     * @param function 函数表达式
     * @param <T>
     * @return 方法返回类型:?
     */
    public static <T> Class<?> getImplFuncType(IGetter<T, ?> function) {
        SerializedLambda serializedLambda = resolve(function);
        String implMethodSignature = serializedLambda.getImplMethodSignature();
        if (implMethodSignature.length() <= 5 && !implMethodSignature.contains("/")) {
            String implMethodName = serializedLambda.getImplMethodName();
            log.error(implMethodName + " ==> 该方法或方法对应的属性使用了基础类型，会造成无法解析，请将属性或方法的返回值换成基础类型对应的包装类");
        }
        implMethodSignature = implMethodSignature.substring(3, implMethodSignature.indexOf(";"));
        try {
            return Class.forName(implMethodSignature.replace('/', '.'));
        } catch (ClassNotFoundException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    // ===== 方法引用元数据（基于 instantiatedMethodType，正确处理继承自父类的 getter） =====

    /**
     * 解析方法引用的接收者类型：{@code User::getId} 返回 {@code User.class}。
     *
     * <p>优先使用 {@code instantiatedMethodType}，因此 {@code User::getId} 即使 {@code getId} 声明在父类，
     * 也返回 {@code User} 而不是父类；解析失败时回退到 {@code implClass}。
     */
    public static Class<?> getReceiverClass(IGetter<?, ?> function) {
        SerializedLambda lambda = resolve(function);
        String type = lambda.getInstantiatedMethodType();
        if (type != null && type.startsWith("(L")) {
            int end = type.indexOf(';');
            if (end > 2) {
                return loadClass(type.substring(2, end).replace('/', '.'));
            }
        }
        return loadClass(lambda.getImplClass().replace('/', '.'));
    }

    /**
     * 解析方法引用的返回值类型（基本类型自动装箱）：{@code User::getAge} 返回 {@code Integer.class}。
     */
    public static Class<?> getReturnType(IGetter<?, ?> function) {
        SerializedLambda lambda = resolve(function);
        String type = lambda.getInstantiatedMethodType();
        if (type == null || type.indexOf(')') < 0) {
            type = lambda.getImplMethodSignature();
        }
        return boxed(descriptorToClass(type.substring(type.indexOf(')') + 1)));
    }

    /**
     * 解析方法引用对应的属性名：{@code getUserName} → {@code userName}，{@code isActive} → {@code active}，
     * record 访问器 {@code name} → {@code name}。
     */
    public static String getPropertyName(IGetter<?, ?> function) {
        String method = resolve(function).getImplMethodName();
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
        switch (descriptor.charAt(0)) {
            case 'I': return int.class;
            case 'J': return long.class;
            case 'Z': return boolean.class;
            case 'D': return double.class;
            case 'F': return float.class;
            case 'S': return short.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'V': return void.class;
            case 'L': return loadClass(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            case '[': return loadClass(descriptor.replace('/', '.'));
            default: throw new TaraException("Unsupported type descriptor: " + descriptor);
        }
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
