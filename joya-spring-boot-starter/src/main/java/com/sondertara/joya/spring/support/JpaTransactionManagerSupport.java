package com.sondertara.joya.spring.support;

import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;

/**
 * JPA 事务管理器的反射适配：starter 不强制依赖 spring-orm，
 * 只有 classpath 上存在 {@code JpaTransactionManager} 时才启用 JPA 事务共享。
 */
public final class JpaTransactionManagerSupport {

    private static final String JPA_MANAGER = "org.springframework.orm.jpa.JpaTransactionManager";
    private static final String JTA_MANAGER = "org.springframework.transaction.jta.JtaTransactionManager";

    private static final Class<?> JPA_MANAGER_CLASS = load(JPA_MANAGER);
    private static final Class<?> JTA_MANAGER_CLASS = load(JTA_MANAGER);

    private JpaTransactionManagerSupport() {
    }

    public static boolean isJpa(PlatformTransactionManager manager) {
        return JPA_MANAGER_CLASS != null && JPA_MANAGER_CLASS.isInstance(manager);
    }

    public static boolean isJta(PlatformTransactionManager manager) {
        return JTA_MANAGER_CLASS != null && JTA_MANAGER_CLASS.isInstance(manager);
    }

    public static DataSource dataSource(PlatformTransactionManager manager) {
        return invoke(manager, "getDataSource", new Class<?>[0], new Object[0], DataSource.class);
    }

    private static <T> T invoke(PlatformTransactionManager manager, String name,
                                Class<?>[] parameterTypes, Object[] args, Class<T> returnType) {
        try {
            Method method = manager.getClass().getMethod(name, parameterTypes);
            Object result = method.invoke(manager, args);
            return result == null ? null : returnType.cast(result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access JpaTransactionManager." + name, e);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, JpaTransactionManagerSupport.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
