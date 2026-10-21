package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.dao.JoyaMapperImpl;
import com.sondertara.joya.utils.TypeUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JDK 动态代理实现 Mapper 代理，零外部依赖，仅使用 JDK 标准库 {@link Proxy}。
 *
 * @author huangxiaohu
 */
public class JdkMapperProxy {

    private final DataOps dataOps;

    public JdkMapperProxy(DataOps dataOps) {
        this.dataOps = dataOps;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends JoyaMapper> T create(Class<T> mapperInterface) {
        Class<?> entityClass = resolveEntityClass(mapperInterface);
        JoyaMapperImpl<?> baseMapper = new JoyaMapperImpl<>(dataOps, (Class) entityClass);
        JoyaExecutorSupport executorSupport = new JoyaExecutorSupport(dataOps, entityClass);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, mapperInterface, method, args);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (method.getDeclaringClass() == JoyaMapper.class) {
                try {
                    return method.invoke(baseMapper, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            }
            return executorSupport.execute(method, args);
        };
        return (T) Proxy.newProxyInstance(
                mapperInterface.getClassLoader(),
                new Class<?>[]{mapperInterface},
                handler);
    }

    public <T> JoyaMapper<T> build(Class<T> entityClass) {
        return new JoyaMapperImpl<>(dataOps, entityClass);
    }

    private static Object handleObjectMethod(Object proxy, Class<?> iface, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> iface.getSimpleName() + "Proxy@" + Integer.toHexString(System.identityHashCode(proxy));
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
        };
    }

    /**
     * 从 Mapper 接口继承树（支持多层父接口继承）解析实体泛型实参。
     */
    private static Class<?> resolveEntityClass(Class<?> mapperInterface) {
        if (mapperInterface == null || !JoyaMapper.class.isAssignableFrom(mapperInterface)) {
            throw new IllegalStateException("The mapper interface must be subclass of the interface[JoyaMapper.class]");
        }
        Class<?> entityClass = TypeUtils.resolveTypeArgument(mapperInterface, JoyaMapper.class);
        if (entityClass == null || entityClass == Object.class) {
            throw new IllegalStateException("The super class[JoyaMapper.class] should with ParameterizedType");
        }
        return entityClass;
    }
}
