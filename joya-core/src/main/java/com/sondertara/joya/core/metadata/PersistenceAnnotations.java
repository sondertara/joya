package com.sondertara.joya.core.metadata;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 持久化注解双栈解析器：同时兼容 {@code jakarta.persistence.*}（Spring Boot 3 / JPA 3+）
 * 与 {@code javax.persistence.*}（Spring Boot 2 / JPA 2.x），且运行时不强依赖任意一方必然存在。
 */
public final class PersistenceAnnotations {

    public record TableMeta(String name, String schema, String catalog) {
    }

    private static final List<Class<? extends Annotation>> TABLE_TYPES = loadAnnotations(
            "jakarta.persistence.Table", "javax.persistence.Table");
    private static final List<Class<? extends Annotation>> ENTITY_TYPES = loadAnnotations(
            "jakarta.persistence.Entity", "javax.persistence.Entity");
    private static final List<Class<? extends Annotation>> COLUMN_TYPES = loadAnnotations(
            "jakarta.persistence.Column", "javax.persistence.Column");
    private static final List<Class<? extends Annotation>> ID_TYPES = loadAnnotations(
            "jakarta.persistence.Id", "javax.persistence.Id");
    private static final List<Class<? extends Annotation>> TRANSIENT_TYPES = loadAnnotations(
            "jakarta.persistence.Transient", "javax.persistence.Transient");

    private PersistenceAnnotations() {
    }

    /**
     * 解析类上的 {@code @Table} 注解元数据。
     */
    public static Optional<TableMeta> findTable(AnnotatedElement element) {
        for (Class<? extends Annotation> type : TABLE_TYPES) {
            Annotation ann = element.getAnnotation(type);
            if (ann != null) {
                String name = invokeString(ann, "name");
                String schema = invokeString(ann, "schema");
                String catalog = invokeString(ann, "catalog");
                return Optional.of(new TableMeta(name, schema, catalog));
            }
        }
        return Optional.empty();
    }

    /**
     * 解析类上的 {@code @Entity(name = ...)} 名称；若未标注 {@code @Entity} 返回 {@link Optional#empty()}。
     */
    public static Optional<String> findEntityName(AnnotatedElement element) {
        for (Class<? extends Annotation> type : ENTITY_TYPES) {
            Annotation ann = element.getAnnotation(type);
            if (ann != null) {
                return Optional.of(invokeString(ann, "name"));
            }
        }
        return Optional.empty();
    }

    /**
     * 判断是否标注了 {@code @Entity}。
     */
    public static boolean isEntityPresent(AnnotatedElement element) {
        return isAnyPresent(element, ENTITY_TYPES);
    }

    /**
     * 判断是否标注了 {@code @Table} 或 {@code @Entity}。
     */
    public static boolean isTableOrEntityPresent(AnnotatedElement element) {
        return isAnyPresent(element, TABLE_TYPES) || isAnyPresent(element, ENTITY_TYPES);
    }

    /**
     * 解析字段或方法上的 {@code @Column(name = ...)} 属性；未标注或为空白字符串时返回 {@link Optional#empty()}。
     */
    public static Optional<String> findColumnName(AnnotatedElement element) {
        for (Class<? extends Annotation> type : COLUMN_TYPES) {
            Annotation ann = element.getAnnotation(type);
            if (ann != null) {
                String name = invokeString(ann, "name");
                if (name != null && !name.isBlank()) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 判断字段是否标注了 {@code @Id}。
     */
    public static boolean isIdPresent(AnnotatedElement element) {
        return isAnyPresent(element, ID_TYPES);
    }

    /**
     * 判断字段是否标注了 {@code @Transient}。
     */
    public static boolean isTransientPresent(AnnotatedElement element) {
        return isAnyPresent(element, TRANSIENT_TYPES);
    }

    private static boolean isAnyPresent(AnnotatedElement element, List<Class<? extends Annotation>> types) {
        for (Class<? extends Annotation> type : types) {
            if (element.isAnnotationPresent(type)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Annotation>> loadAnnotations(String... classNames) {
        ClassLoader cl = PersistenceAnnotations.class.getClassLoader();
        java.util.ArrayList<Class<? extends Annotation>> result = new java.util.ArrayList<>(classNames.length);
        for (String name : classNames) {
            try {
                result.add((Class<? extends Annotation>) Class.forName(name, false, cl));
            } catch (ClassNotFoundException ignored) {
            }
        }
        return List.copyOf(result);
    }

    private static String invokeString(Annotation annotation, String attributeName) {
        try {
            Method m = annotation.annotationType().getMethod(attributeName);
            Object val = m.invoke(annotation);
            return val instanceof String s ? s : "";
        } catch (Exception e) {
            return "";
        }
    }
}
