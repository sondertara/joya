package com.sondertara.joya.core.mapper;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.utils.TypeUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JDK 动态代理实现 Mapper 代理，零外部依赖，且通过 {@link ClassValue} 缓存接口实体泛型实参（短事务内零重复反射）。
 */
public class JdkMapperProxy {

    private static final ClassValue<Class<?>> ENTITY_CLASS_CACHE = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> mapperInterface) {
            Class<?> entityClass = TypeUtils.resolveTypeArgument(mapperInterface, JoyaMapper.class);
            if (entityClass == null || entityClass == Object.class) {
                throw new IllegalStateException("The super class[JoyaMapper.class] should with ParameterizedType");
            }
            return entityClass;
        }
    };

    private final DataOps dataOps;

    public JdkMapperProxy(DataOps dataOps) {
        this.dataOps = dataOps;
    }

    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> mapperInterface) {
        Class<?> entityClass = resolveEntityClass(mapperInterface);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, mapperInterface, method, args);
            }
            if (method.getParameterCount() == 0) {
                if ("db".equals(method.getName())) {
                    return dataOps;
                }
                if ("entityClass".equals(method.getName())) {
                    return entityClass;
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new UnsupportedOperationException(
                    "Abstract method [" + method + "] is not supported in JoyaMapper; "
                            + "declare a 'default' method using query(), ops(), or sql(...) instead.");
        };
        return (T) Proxy.newProxyInstance(
                mapperInterface.getClassLoader(),
                new Class<?>[]{mapperInterface},
                handler);
    }

    private static Object handleObjectMethod(Object proxy, Class<?> iface, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> iface.getSimpleName() + "Proxy@" + Integer.toHexString(System.identityHashCode(proxy));
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
        };
    }

    private static Class<?> resolveEntityClass(Class<?> mapperInterface) {
        if (mapperInterface == null || !JoyaMapper.class.isAssignableFrom(mapperInterface)) {
            throw new IllegalStateException("The mapper interface must be subclass of the interface[JoyaMapper.class]");
        }
        return ENTITY_CLASS_CACHE.get(mapperInterface);
    }
}
