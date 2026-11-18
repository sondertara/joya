package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.MethodMetadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Joya 扩展点 Bean 发现与路由：
 * <ul>
 *   <li>多值扩展点（{@code SqlRewriter} / {@code SqlExecutionListener}）：
 *       无任何限定符的全局 Bean + 限定为 {@code @Qualifier(clientName)} 的 Bean；支持识别 Spring {@link Order}
 *       （含 {@code @Bean} 工厂方法与类注解）及 {@link org.springframework.core.Ordered}；</li>
 *   <li>单值扩展点（{@code RowMapperFactory} / {@code SqlExceptionTranslator} /
 *       {@code TableNameResolver} / {@code Executor.Factory}）：
 *       限定名优先，同一级别多个候选启动失败；带其他自定义无值限定符的专用 Bean 不会被误判为全局候选；</li>
 *   <li>Bean 正在创建（循环依赖）时返回惰性委托，排序在首次执行时进行。</li>
 * </ul>
 */
final class JoyaExtensionPointResolver {

    record QualifierMetadata(boolean hasAnyQualifier, Set<String> values) {
    }

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
            QualifierMetadata qualifiers = extractQualifiers(beanFactory, beanName);
            if (!qualifiers.hasAnyQualifier() || qualifiers.values().contains(clientName)) {
                matched.add(resolveExtensionBean(beanName, type));
            }
        }
        return matched;
    }

    /**
     * 解析单例型扩展点：限定为当前客户端的 Bean 优先，否则取无任何限定符的全局 Bean；同一级别多个候选显式报错。
     */
    <T> T singleForClient(Class<T> type, String clientName) {
        List<String> scoped = new ArrayList<>();
        List<String> global = new ArrayList<>();
        for (String beanName : beanNamesForType(type)) {
            QualifierMetadata qualifiers = extractQualifiers(beanFactory, beanName);
            if (qualifiers.values().contains(clientName)) {
                scoped.add(beanName);
            } else if (!qualifiers.hasAnyQualifier()) {
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
     * 解析扩展点实例：为 {@link SqlRewriter} 与 {@link SqlExecutionListener} 桥接 Spring {@link Order} /
     * {@link org.springframework.core.Ordered}；当 Bean 正在创建时使用惰性委托推迟到首次执行时获取。
     */
    @SuppressWarnings("unchecked")
    private <T> T resolveExtensionBean(String beanName, Class<T> type) {
        OptionalInt springAnnotationOrder = extractOrderAnnotation(beanName);
        try {
            T eager = beanFactory.getBean(beanName, type);
            return adaptOrderedIfNeeded(eager, type, springAnnotationOrder);
        } catch (RuntimeException ex) {
            if (!isCurrentlyInCreationFailure(ex) || !type.isInterface()) {
                throw ex;
            }
            Supplier<T> lazyTarget = new LazyBeanSupplier<>(beanFactory, beanName, type);
            if (type == SqlRewriter.class) {
                return (T) new SpringOrderedSqlRewriter(
                        (Supplier<SqlRewriter>) lazyTarget, springAnnotationOrder);
            }
            if (type == SqlExecutionListener.class) {
                return (T) new SpringOrderedSqlExecutionListener(
                        (Supplier<SqlExecutionListener>) lazyTarget, springAnnotationOrder);
            }
            return (T) Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[]{type},
                    (proxy, method, args) -> {
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
                            return method.invoke(lazyTarget.get(), args);
                        } catch (InvocationTargetException ite) {
                            throw ite.getTargetException();
                        }
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T adaptOrderedIfNeeded(T instance, Class<T> type, OptionalInt annotationOrder) {
        boolean needsSpringOrderBridge = annotationOrder.isPresent()
                || instance instanceof org.springframework.core.Ordered;
        if (!needsSpringOrderBridge) {
            return instance;
        }
        if (type == SqlRewriter.class && instance instanceof SqlRewriter rewriter
                && !overridesJoyaOrder(rewriter.getClass(), SqlRewriter.class)) {
            return (T) new SpringOrderedSqlRewriter(() -> rewriter, annotationOrder);
        }
        if (type == SqlExecutionListener.class && instance instanceof SqlExecutionListener listener
                && !overridesJoyaOrder(listener.getClass(), SqlExecutionListener.class)) {
            return (T) new SpringOrderedSqlExecutionListener(() -> listener, annotationOrder);
        }
        return instance;
    }

    private OptionalInt extractOrderAnnotation(String beanName) {
        if (beanFactory.containsBeanDefinition(beanName)) {
            BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);
            if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
                MethodMetadata factoryMethod = annotatedBd.getFactoryMethodMetadata();
                if (factoryMethod != null) {
                    MergedAnnotation<Order> methodOrder = factoryMethod.getAnnotations().get(Order.class);
                    if (methodOrder.isPresent()) {
                        return OptionalInt.of(methodOrder.getInt("value"));
                    }
                }
                MergedAnnotation<Order> classOrder = annotatedBd.getMetadata().getAnnotations().get(Order.class);
                if (classOrder.isPresent()) {
                    return OptionalInt.of(classOrder.getInt("value"));
                }
            }
        }
        Order found = beanFactory.findAnnotationOnBean(beanName, Order.class, false);
        return found != null ? OptionalInt.of(found.value()) : OptionalInt.empty();
    }

    private static int resolveEffectiveOrder(Object target, Class<?> joyaInterface, OptionalInt annotationOrder) {
        if (overridesJoyaOrder(target.getClass(), joyaInterface)) {
            return ((com.sondertara.joya.core.kernel.support.Ordered) target).order();
        }
        if (target instanceof org.springframework.core.Ordered springOrdered) {
            return springOrdered.getOrder();
        }
        if (annotationOrder.isPresent()) {
            return annotationOrder.getAsInt();
        }
        return ((com.sondertara.joya.core.kernel.support.Ordered) target).order();
    }

    private static boolean overridesJoyaOrder(Class<?> implClass, Class<?> joyaInterface) {
        try {
            Method method = implClass.getMethod("order");
            Class<?> declaring = method.getDeclaringClass();
            return declaring != joyaInterface
                    && declaring != com.sondertara.joya.core.kernel.support.Ordered.class;
        } catch (NoSuchMethodException e) {
            return false;
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

    static QualifierMetadata extractQualifiers(ConfigurableListableBeanFactory beanFactory, String beanName) {
        boolean[] hasAny = {false};
        Set<String> values = new LinkedHashSet<>();
        if (beanFactory.containsBeanDefinition(beanName)) {
            BeanDefinition bd = beanFactory.getMergedBeanDefinition(beanName);
            collectQualifiersFromBeanDefinition(bd, hasAny, values);
        }
        Set<Qualifier> onBean = beanFactory.findAllAnnotationsOnBean(beanName, Qualifier.class, false);
        if (!onBean.isEmpty()) {
            hasAny[0] = true;
            for (Qualifier q : onBean) {
                if (q.value() != null && !q.value().isBlank()) {
                    values.add(q.value());
                }
            }
        }
        return new QualifierMetadata(hasAny[0], values);
    }

    private static void collectQualifiersFromBeanDefinition(BeanDefinition bd, boolean[] hasAny, Set<String> values) {
        if (bd instanceof AbstractBeanDefinition abd) {
            for (AutowireCandidateQualifier q : abd.getQualifiers()) {
                hasAny[0] = true;
                Object val = q.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
                if (val instanceof String s && !s.isBlank()) {
                    values.add(s);
                }
            }
        }
        if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
            MethodMetadata factoryMethod = annotatedBd.getFactoryMethodMetadata();
            if (factoryMethod != null) {
                collectQualifierFromAnnotations(factoryMethod.getAnnotations(), hasAny, values);
            }
            collectQualifierFromAnnotations(annotatedBd.getMetadata().getAnnotations(), hasAny, values);
        }
    }

    private static void collectQualifierFromAnnotations(MergedAnnotations annotations,
                                                        boolean[] hasAny,
                                                        Set<String> values) {
        for (MergedAnnotation<Annotation> ann : annotations) {
            String typeName = ann.getType().getName();
            if (Qualifier.class.equals(ann.getType())) {
                hasAny[0] = true;
                String val = ann.getString("value");
                if (!val.isBlank()) {
                    values.add(val);
                }
            } else if ("jakarta.inject.Qualifier".equals(typeName) || "javax.inject.Qualifier".equals(typeName)) {
                hasAny[0] = true;
            }
        }
    }

    private static final class LazyBeanSupplier<T> implements Supplier<T> {
        private final ConfigurableListableBeanFactory beanFactory;
        private final String beanName;
        private final Class<T> type;
        private volatile T cached;

        LazyBeanSupplier(ConfigurableListableBeanFactory beanFactory, String beanName, Class<T> type) {
            this.beanFactory = beanFactory;
            this.beanName = beanName;
            this.type = type;
        }

        @Override
        public T get() {
            T resolved = cached;
            if (resolved == null) {
                synchronized (this) {
                    resolved = cached;
                    if (resolved == null) {
                        resolved = beanFactory.getBean(beanName, type);
                        cached = resolved;
                    }
                }
            }
            return resolved;
        }
    }

    private static final class SpringOrderedSqlRewriter implements SqlRewriter {
        private final Supplier<SqlRewriter> delegate;
        private final OptionalInt annotationOrder;

        SpringOrderedSqlRewriter(Supplier<SqlRewriter> delegate, OptionalInt annotationOrder) {
            this.delegate = delegate;
            this.annotationOrder = annotationOrder;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            return delegate.get().rewrite(node, ctx);
        }

        @Override
        public boolean supports(SqlRewriteContext ctx) {
            return delegate.get().supports(ctx);
        }

        @Override
        public int order() {
            return resolveEffectiveOrder(delegate.get(), SqlRewriter.class, annotationOrder);
        }
    }

    private static final class SpringOrderedSqlExecutionListener implements SqlExecutionListener {
        private final Supplier<SqlExecutionListener> delegate;
        private final OptionalInt annotationOrder;

        SpringOrderedSqlExecutionListener(Supplier<SqlExecutionListener> delegate, OptionalInt annotationOrder) {
            this.delegate = delegate;
            this.annotationOrder = annotationOrder;
        }

        @Override
        public void beforeExecute(SqlExecutionContext ctx) {
            delegate.get().beforeExecute(ctx);
        }

        @Override
        public void afterExecute(SqlExecutionContext ctx, SqlExecutionResult result) {
            delegate.get().afterExecute(ctx, result);
        }

        @Override
        public void onError(SqlExecutionContext ctx, Throwable error, long elapsedNanos) {
            delegate.get().onError(ctx, error, elapsedNanos);
        }

        @Override
        public void onStreamClosed(SqlExecutionContext ctx) {
            delegate.get().onStreamClosed(ctx);
        }

        @Override
        public int order() {
            return resolveEffectiveOrder(delegate.get(), SqlExecutionListener.class, annotationOrder);
        }
    }
}
