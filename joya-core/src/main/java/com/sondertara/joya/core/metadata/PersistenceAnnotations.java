package com.sondertara.joya.core.metadata;

import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * 持久化注解双栈解析器：同时兼容 {@code jakarta.persistence.*}（Spring Boot 3 / JPA 3+）
 * 与 {@code javax.persistence.*}（Spring Boot 2 / JPA 2.x），且运行时不强依赖任意一方必然存在。
 */
public final class PersistenceAnnotations {

    public record TableMeta(String name, String schema, String catalog) {
    }

    public record ColumnMeta(String name, boolean insertable, boolean updatable) {
    }

    private static final List<Class<? extends Annotation>> TABLE_TYPES = loadAnnotations(
            "jakarta.persistence.Table", "javax.persistence.Table");
    private static final List<Class<? extends Annotation>> ENTITY_TYPES = loadAnnotations(
            "jakarta.persistence.Entity", "javax.persistence.Entity");
    private static final List<Class<? extends Annotation>> COLUMN_TYPES = loadAnnotations(
            "jakarta.persistence.Column", "javax.persistence.Column");
    private static final List<Class<? extends Annotation>> ID_TYPES = loadAnnotations(
            "jakarta.persistence.Id", "javax.persistence.Id");
    private static final List<Class<? extends Annotation>> GENERATED_VALUE_TYPES = loadAnnotations(
            "jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue");
    private static final List<Class<? extends Annotation>> TRANSIENT_TYPES = loadAnnotations(
            "jakarta.persistence.Transient", "javax.persistence.Transient");
    private static final List<Class<? extends Annotation>> ID_CLASS_TYPES = loadAnnotations(
            "jakarta.persistence.IdClass", "javax.persistence.IdClass");
    private static final List<Class<? extends Annotation>> EMBEDDED_ID_TYPES = loadAnnotations(
            "jakarta.persistence.EmbeddedId", "javax.persistence.EmbeddedId");
    private static final List<Class<? extends Annotation>> VERSION_TYPES = loadAnnotations(
            "jakarta.persistence.Version", "javax.persistence.Version");
    private static final List<Class<? extends Annotation>> CONVERT_TYPES = loadAnnotations(
            "jakarta.persistence.Convert", "javax.persistence.Convert");
    private static final List<Class<?>> ATTRIBUTE_CONVERTER_INTERFACES = loadInterfaces(
            "jakarta.persistence.AttributeConverter", "javax.persistence.AttributeConverter");

    private static final MethodType UNARY_OBJECT_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();

    /** 按 AttributeConverter 实现类缓存预绑定 MethodHandle 的无状态转换器句柄。 */
    private static final ClassValue<ColumnConverter> CONVERTER_CACHE = new ClassValue<>() {
        @Override
        protected ColumnConverter computeValue(Class<?> converterClass) {
            return compileConverter(converterClass);
        }
    };

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
     * 解析字段或方法上的 {@code @Column} 完整元数据（名称、insertable、updatable）。
     */
    public static Optional<ColumnMeta> findColumn(AnnotatedElement element) {
        for (Class<? extends Annotation> type : COLUMN_TYPES) {
            Annotation ann = element.getAnnotation(type);
            if (ann != null) {
                String name = invokeString(ann, "name");
                boolean insertable = invokeBoolean(ann, "insertable", true);
                boolean updatable = invokeBoolean(ann, "updatable", true);
                return Optional.of(new ColumnMeta(name, insertable, updatable));
            }
        }
        return Optional.empty();
    }

    /**
     * 解析字段或方法上的 {@code @Column(name = ...)} 属性；未标注或为空白字符串时返回 {@link Optional#empty()}。
     */
    public static Optional<String> findColumnName(AnnotatedElement element) {
        return findColumn(element)
                .map(ColumnMeta::name)
                .filter(name -> name != null && !name.isBlank());
    }

    /**
     * 判断字段是否标注了 {@code @Id}。
     */
    public static boolean isIdPresent(AnnotatedElement element) {
        return isAnyPresent(element, ID_TYPES);
    }

    /**
     * 判断字段是否标注了 {@code @GeneratedValue}。
     */
    public static boolean isGeneratedValuePresent(AnnotatedElement element) {
        return isAnyPresent(element, GENERATED_VALUE_TYPES);
    }

    /**
     * 判断字段是否标注了 {@code @Transient}。
     */
    public static boolean isTransientPresent(AnnotatedElement element) {
        return isAnyPresent(element, TRANSIENT_TYPES);
    }

    /**
     * 判断类是否标注了 {@code @IdClass}（复合主键）。
     */
    public static boolean isIdClassPresent(AnnotatedElement element) {
        return isAnyPresent(element, ID_CLASS_TYPES);
    }

    /**
     * 判断字段是否标注了 {@code @EmbeddedId}（嵌入式复合主键）。
     */
    public static boolean isEmbeddedIdPresent(AnnotatedElement element) {
        return isAnyPresent(element, EMBEDDED_ID_TYPES);
    }

    /**
     * 判断字段是否标注了 {@code @Version}（乐观锁版本列）。
     */
    public static boolean isVersionPresent(AnnotatedElement element) {
        return isAnyPresent(element, VERSION_TYPES);
    }

    /**
     * 解析字段或方法上的标准 JPA {@code @Convert(converter = ...)} 双向列转换器。
     * 若未标注、或 {@code disableConversion = true}、或 {@code converter == void.class} 则返回空。
     */
    public static Optional<ColumnConverter> findConverter(AnnotatedElement element) {
        for (Class<? extends Annotation> type : CONVERT_TYPES) {
            Annotation ann = element.getAnnotation(type);
            if (ann != null) {
                if (invokeBoolean(ann, "disableConversion", false)) {
                    return Optional.empty();
                }
                Class<?> converterClass = invokeClass(ann, "converter");
                if (converterClass == null || converterClass == void.class) {
                    return Optional.empty();
                }
                return Optional.of(CONVERTER_CACHE.get(converterClass));
            }
        }
        return Optional.empty();
    }

    /**
     * 统一解析实体/DTO 字段及其对应 Getter/Record 访问器上的标准 JPA {@code @Convert}，
     * 保证写路径（{@code LocalTableMetaCache}）与读路径（{@code BeanRowMapper}）采用完全一致的单一解析真源。
     */
    public static Optional<ColumnConverter> findConverter(Class<?> ownerType, Field field) {
        Optional<ColumnConverter> onField = findConverter(field);
        if (onField.isPresent()) {
            return onField;
        }
        String fieldName = field.getName();
        if (fieldName.isEmpty()) {
            return Optional.empty();
        }
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String[] candidates = ownerType.isRecord()
                ? new String[]{fieldName, "get" + capitalized, "is" + capitalized}
                : new String[]{"get" + capitalized, "is" + capitalized};
        Class<?> declaring = field.getDeclaringClass();
        for (String name : candidates) {
            Method method = findZeroArgMethod(ownerType, declaring, name);
            if (method != null) {
                Optional<ColumnConverter> onMethod = findConverter(method);
                if (onMethod.isPresent()) {
                    return onMethod;
                }
            }
        }
        return Optional.empty();
    }

    private static @Nullable Method findZeroArgMethod(Class<?> ownerType, Class<?> declaringClass, String name) {
        try {
            return ownerType.getMethod(name);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return declaringClass.getDeclaredMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static ColumnConverter compileConverter(Class<?> converterClass) {
        Class<?> matchedInterface = null;
        for (Class<?> iface : ATTRIBUTE_CONVERTER_INTERFACES) {
            if (iface.isAssignableFrom(converterClass)) {
                matchedInterface = iface;
                break;
            }
        }
        if (matchedInterface == null) {
            throw new DbException("@Convert converter [" + converterClass.getName()
                    + "] must implement jakarta.persistence.AttributeConverter or javax.persistence.AttributeConverter");
        }
        Object instance;
        try {
            Constructor<?> ctor = converterClass.getDeclaredConstructor();
            if (!ctor.canAccess(null)) {
                ctor.trySetAccessible();
            }
            instance = ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new DbException("Failed to instantiate @Convert AttributeConverter ["
                    + converterClass.getName() + "]; a no-arg constructor is required", e);
        }
        try {
            MethodHandle toDb = PUBLIC_LOOKUP.findVirtual(matchedInterface, "convertToDatabaseColumn", UNARY_OBJECT_TYPE)
                    .bindTo(instance);
            MethodHandle toEntity = PUBLIC_LOOKUP.findVirtual(matchedInterface, "convertToEntityAttribute", UNARY_OBJECT_TYPE)
                    .bindTo(instance);
            Class<?>[] args = TypeUtils.resolveTypeArguments(converterClass, matchedInterface);
            Class<?> entityType = (args.length >= 1 && args[0] != null) ? args[0] : Object.class;
            Class<?> dbType = (args.length >= 2 && args[1] != null) ? args[1] : Object.class;
            return new ColumnConverter(converterClass, entityType, dbType, toDb, toEntity);
        } catch (ReflectiveOperationException e) {
            throw new DbException("Failed to bind AttributeConverter methods on [" + converterClass.getName() + "]", e);
        }
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

    private static List<Class<?>> loadInterfaces(String... classNames) {
        ClassLoader cl = PersistenceAnnotations.class.getClassLoader();
        java.util.ArrayList<Class<?>> result = new java.util.ArrayList<>(classNames.length);
        for (String name : classNames) {
            try {
                result.add(Class.forName(name, false, cl));
            } catch (ClassNotFoundException ignored) {
            }
        }
        return List.copyOf(result);
    }

    private static Class<?> invokeClass(Annotation annotation, String attributeName) {
        try {
            Method m = annotation.annotationType().getMethod(attributeName);
            Object val = m.invoke(annotation);
            return val instanceof Class<?> c ? c : null;
        } catch (Exception e) {
            return null;
        }
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

    private static boolean invokeBoolean(Annotation annotation, String attributeName, boolean defaultValue) {
        try {
            Method m = annotation.annotationType().getMethod(attributeName);
            Object val = m.invoke(annotation);
            return val instanceof Boolean b ? b : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
