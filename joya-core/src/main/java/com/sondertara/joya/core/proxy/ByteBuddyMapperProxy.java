package com.sondertara.joya.core.proxy;


import com.sondertara.common.classpath.ClassLoaderUtils;
import com.sondertara.common.reflect.type.TypeUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.dao.SimpleJoyaMapper;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;


/**
 * @author huangxiaohu
 */
@SuppressWarnings("unchecked")
public class ByteBuddyMapperProxy implements JoyaMapperProxy {

    private final JoyaJdbc joyaJdbc;

    public ByteBuddyMapperProxy(JoyaJdbc joyaJdbc) {
        this.joyaJdbc = joyaJdbc;
    }

    @Override
    public <T extends JoyaMapper> T create(Class<T> mapperInterface) {
        Type[] interfaces = mapperInterface.getGenericInterfaces();
        Class<?> entityClass = null;
        Type argument;
        for (Type anInterface : interfaces) {
            if (anInterface.getTypeName().contains(JoyaMapper.class.getCanonicalName())) {
                if (anInterface.getTypeName().length() == (JoyaMapper.class.getCanonicalName().length())) {
                    throw new IllegalStateException("The super class[JoyaMapper.class] should with ParameterizedType");
                }
                argument = TypeUtils.getTypeArgument(anInterface);
                entityClass = TypeUtils.getClass(argument);
                break;
            }
        }

        if (entityClass == null) {
            throw new IllegalStateException("The mapper interface must be subclass of the interface[JoyaMapper.class]");
        }

        //InvocationHandlerAdapter.of(new InvocationHandler() {
        //    @Override
        //    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        //
        //        return method.invoke(repository, args);
        //    }
        //})
        //Class<?> aClass = new ByteBuddy()
        //        .subclass(TypeDescription.Generic.Builder.parameterizedType(SimpleJoyaRepository.class, entityClass).build())
        //        .implement(clazz)
        //        .method(ElementMatchers.isDeclaredBy(clazz))
        //        .intercept(MethodDelegation.to(new TestInterceptor(entityClass))).make()
        //        .load(ClassLoaderUtils.getClassLoader())
        //        .getLoaded();
        //return ReflectUtils.newInstance(aClass, entityClass);
        try {
            return new ByteBuddy()
                    .subclass(mapperInterface)
                    .method(ElementMatchers.isDeclaredBy(JoyaMapper.class))
                    .intercept(InvocationHandlerAdapter.of(new JoyaMapperInvocationHandler(joyaJdbc, entityClass)))
                    .method(ElementMatchers.isDeclaredBy(mapperInterface))
                    .intercept(MethodDelegation.to(new AnnotationInterceptor(joyaJdbc, entityClass)))
                    .make()
                    .load(ClassLoaderUtils.getClassLoader()).
                    getLoaded()
                    .newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> JoyaMapper<T> build(Class<T> entityClass) {
        try {
            TypeDescription.Generic generic = TypeDescription.Generic.Builder.parameterizedType(JoyaMapper.class, entityClass).build();
            Object instance = new ByteBuddy()
                    .subclass(generic)
                    .method(ElementMatchers.isDeclaredBy(JoyaMapper.class))
                    .intercept(InvocationHandlerAdapter.of(new JoyaMapperInvocationHandler(joyaJdbc, entityClass)))
                    .make()
                    .load(ClassLoaderUtils.getClassLoader()).
                    getLoaded()
                    .newInstance();
            return (JoyaMapper<T>) instance;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Slf4j
    public static class AnnotationInterceptor {
        private final JoyaExecutorSupport joyaExecutorSupport;

        public AnnotationInterceptor(JoyaJdbc joyaJdbc, Class<?> entityClass) {
            if (log.isDebugEnabled()) {
                log.debug("Joya Proxy interceptor for class[{}]", entityClass.getName());
            }

            this.joyaExecutorSupport = new JoyaExecutorSupport(joyaJdbc, entityClass);
        }

        @RuntimeType
        public Object interceptor(@This Object instance, @Origin Method method, @AllArguments Object[] args) {

            return joyaExecutorSupport.execute(method, args);

        }
    }

    public static class JoyaMapperInvocationHandler implements InvocationHandler {


        private final SimpleJoyaMapper<?> repository;

        public JoyaMapperInvocationHandler(JoyaJdbc joyaJdbc, Class<?> entityClass) {
            repository = new SimpleJoyaMapper<>(joyaJdbc, entityClass);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return method.invoke(repository, args);
        }
    }

}
