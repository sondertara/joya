package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.dao.JoyaMapperImpl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * JDK 动态代理实现 Mapper 代理，替代 ByteBuddyMapperProxy。
 * <p>
 * 零外部依赖，仅使用 JDK 标准库 {@link Proxy}。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@link #build(Class)}：给 {@link JoyaMapper} 接口生成实现，委托 {@link JoyaMapperImpl}</li>
 *   <li>{@link #create(Class)}：给自定义 Mapper 接口生成实现，基础方法委托 {@link JoyaMapperImpl}，自定义方法委托 {@link JoyaExecutorSupport}（支持 {@code @Query} 注解和方法名派生查询）</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public class JdkMapperProxy implements JoyaMapperProxy {

    private final DataOps dataOps;

    public JdkMapperProxy(DataOps dataOps) {
        this.dataOps = dataOps;
    }

    @Override
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

    @Override
    @SuppressWarnings("unchecked")
    public <T> JoyaMapper<T> build(Class<T> entityClass) {
        JoyaMapperImpl<T> impl = new JoyaMapperImpl<>(dataOps, entityClass);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, JoyaMapper.class, method, args);
            }
            try {
                return method.invoke(impl, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return (JoyaMapper<T>) Proxy.newProxyInstance(
                JoyaMapper.class.getClassLoader(),
                new Class<?>[]{JoyaMapper.class},
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

    /**
     * 从 Mapper 接口的泛型参数解析实体类型。
     * 例如：interface UserMapper extends JoyaMapper<UserPo> -> 返回 UserPo.class
     */
    private Class<?> resolveEntityClass(Class<?> mapperInterface) {
        for (Type genericInterface : mapperInterface.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt && pt.getRawType() == JoyaMapper.class) {
                Type actualType = pt.getActualTypeArguments()[0];
                if (actualType instanceof Class<?> clazz) {
                    return clazz;
                }
            } else if (genericInterface == JoyaMapper.class) {
                throw new IllegalStateException("The super class[JoyaMapper.class] should with ParameterizedType");
            }
        }
        throw new IllegalStateException("The mapper interface must be subclass of the interface[JoyaMapper.class]");
    }
}