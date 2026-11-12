package com.sondertara.joya.spring.autoconfigure;

import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.MethodMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Joya 扩展点 Bean 发现与路由：
 * <ul>
 *   <li>多值扩展点（{@code SqlRewriter} / {@code SqlExecutionListener} / {@code EntityListener}）：
 *       无 {@code @Qualifier} 的全局 Bean + 限定为 {@code @Qualifier(clientName)} 的 Bean；</li>
 *   <li>单值扩展点（{@code RowMapperFactory} / {@code SqlExceptionTranslator}）：
 *       限定名优先，同一级别多个候选启动失败；</li>
 *   <li>Bean 正在创建（循环依赖）时返回惰性代理，排序在首次执行时进行。</li>
 * </ul>
 */
final class JoyaExtensionPointResolver {

    private final ConfigurableListableBeanFactory beanFactory;

    JoyaExtensionPointResolver(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 收集对指定客户端生效的扩展点 Bean。
     */
    <T> List<T> forClient(Class<T> type, String clientName) {
        String[] beanNames = beanNamesForType(type);
        if (beanNames.length == 0) {
            return List.of();
        }
        List<T> matched = new ArrayList<>();
        for (String beanName : beanNames) {
            Set<String> qualifiers = extractQualifierValues(beanName);
            if (qualifiers.isEmpty() || qualifiers.contains(clientName)) {
                matched.add(resolveExtensionBean(beanName, type));
            }
        }
        return matched;
    }

    /**
     * 解析单例型扩展点：限定为当前客户端的 Bean 优先，否则取全局 Bean；同一级别多个候选显式报错。
     */
    <T> T singleForClient(Class<T> type, String clientName) {
        List<String> scoped = new ArrayList<>();
        List<String> global = new ArrayList<>();
        for (String beanName : beanNamesForType(type)) {
            Set<String> qualifiers = extractQualifierValues(beanName);
            if (qualifiers.contains(clientName)) {
                scoped.add(beanName);
            } else if (qualifiers.isEmpty()) {
                global.add(beanName);
            }
        }
        List<String> candidates = scoped.isEmpty() ? global : scoped;
        if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple " + type.getSimpleName() + " beans match Joya client '"
                    + clientName + "': " + candidates + ". Keep exactly one global bean, or qualify one per client "
                    + "with @Qualifier(\"" + clientName + "\").");
        }
        return candidates.isEmpty() ? null : resolveExtensionBean(candidates.get(0), type);
    }

    /**
     * Bean 正在创建时返回惰性代理：调用发生在容器就绪后（排序也延迟到首次执行），
     * 因此代理不再伪造 {@code order()} 等元信息。
     */
    @SuppressWarnings("unchecked")
    private <T> T resolveExtensionBean(String beanName, Class<T> type) {
        try {
            return beanFactory.getBean(beanName, type);
        } catch (RuntimeException ex) {
            if (!isCurrentlyInCreationFailure(ex) || !type.isInterface()) {
                throw ex;
            }
            return (T) Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[]{type},
                    new java.lang.reflect.InvocationHandler() {
                        private volatile T delegate;

                        private T target() {
                            T resolved = delegate;
                            if (resolved == null) {
                                synchronized (this) {
                                    resolved = delegate;
                                    if (resolved == null) {
                                        resolved = beanFactory.getBean(beanName, type);
                                        delegate = resolved;
                                    }
                                }
                            }
                            return resolved;
                        }

                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                                throws Throwable {
                            String name = method.getName();
                            if ("equals".equals(name)) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("toString".equals(name)) {
                                return "LazyJoyaExtensionProxy[" + beanName + "]";
                            }
                            try {
                                return method.invoke(target(), args);
                            } catch (InvocationTargetException ite) {
                                throw ite.getTargetException();
                            }
                        }
                    });
        }
    }

    /**
     * FactoryBean 的 {@code getObjectType()} 往往需要初始化后才能确定类型；若不允许 eager init，
     * 这类扩展点在 {@code getBeanNamesForType(type,false,false)} 下会被静默跳过（E-4）。
     */
    private String[] beanNamesForType(Class<?> type) {
        return beanFactory.getBeanNamesForType(type, false, true);
    }

    private static boolean isCurrentlyInCreationFailure(Throwable ex) {
        for (Throwable cur = ex; cur != null; cur = cur.getCause()) {
            if (cur instanceof BeanCurrentlyInCreationException) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractQualifierValues(String beanName) {
        Set<String> values = new LinkedHashSet<>();
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return values;
        }
        BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);
        if (bd instanceof AbstractBeanDefinition abd) {
            for (AutowireCandidateQualifier q : abd.getQualifiers()) {
                Object val = q.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
                if (val instanceof String s && !s.isBlank()) {
                    values.add(s);
                }
            }
        }
        if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
            MethodMetadata factoryMethod = annotatedBd.getFactoryMethodMetadata();
            if (factoryMethod != null) {
                collectQualifierFromAnnotations(factoryMethod.getAnnotations(), values);
            }
            collectQualifierFromAnnotations(annotatedBd.getMetadata().getAnnotations(), values);
        }
        return values;
    }

    private static void collectQualifierFromAnnotations(MergedAnnotations annotations, Set<String> values) {
        for (MergedAnnotation<Annotation> ann : annotations) {
            if (Qualifier.class.equals(ann.getType())) {
                String val = ann.getString("value");
                if (!val.isBlank()) {
                    values.add(val);
                }
            }
        }
    }
}
