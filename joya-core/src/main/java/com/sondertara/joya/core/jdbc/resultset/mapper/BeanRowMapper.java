package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;

import com.sondertara.joya.core.function.TypedConverter;
import com.sondertara.joya.utils.ClassUtils;
import com.sondertara.joya.utils.CollectionUtils;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.PropertyUtils;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.core.jdbc.resultset.FieldAlias;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将一行数据转换成 JavaBean 或 Java 17 Record。
 *
 * @author huangxiaohu
 */
@Slf4j
public class BeanRowMapper<T> implements RowMapper<T> {

    private static final ClassValue<BeanRowMapper<?>> CACHE = new ClassValue<>() {
        @Override
        protected BeanRowMapper<?> computeValue(Class<?> type) {
            return new BeanRowMapper<>(type);
        }
    };

    /**
     * 获取指定类型的缓存级不可变 {@link BeanRowMapper} 实例（基于 {@link ClassValue} 零锁争用复用反射元数据）。
     */
    @SuppressWarnings("unchecked")
    public static <T> BeanRowMapper<T> of(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return (BeanRowMapper<T>) CACHE.get(type);
    }

    private final Class<T> type;

    private LinkedCaseInsensitiveMap<PropertyTarget> mappedTargets;

    @Nullable
    private LinkedCaseInsensitiveMap<String> columnPropertyAlias = null;

    private Map<String, TypedConverter> fieldTypeConverterMap;

    @Nullable
    private EntityMetadataContext metadataContext;

    @Nullable
    private Constructor<T> defaultConstructor;

    private final boolean isRecord;
    @Nullable
    private RecordComponent[] recordComponents;
    @Nullable
    private Constructor<T> recordConstructor;
    @Nullable
    private LinkedCaseInsensitiveMap<Integer> recordComponentIndex;

    private record PropertyTarget(String name, Class<?> type, @Nullable Method setter, @Nullable Field field) {
        void write(Object bean, Object value) throws Exception {
            if (setter != null) {
                setter.invoke(bean, value);
            } else if (field != null) {
                field.set(bean, value);
            }
        }
    }

    public BeanRowMapper(Class<T> type) {
        this.type = type;
        this.isRecord = type != null && type.isRecord();
        initialize();
    }


    private void initialize() {
        this.mappedTargets = new LinkedCaseInsensitiveMap<>();
        this.fieldTypeConverterMap = new HashMap<>();
        if (this.isRecord) {
            initializeRecord();
            return;
        }
        if (type == null || type.isInterface() || isSimpleValueType(type)) {
            return;
        }
        try {
            this.defaultConstructor = type.getDeclaredConstructor();
            this.defaultConstructor.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        if (PersistenceAnnotations.isTableOrEntityPresent(type)) {
            metadataContext = CacheHolder.tableMetaCache().getMetadataContext(type);
        }

        Map<String, Method> writeMethods = new HashMap<>();
        Collection<PropertyDescriptor> descriptors = PropertyUtils.getPropertyDescriptors(type);
        for (PropertyDescriptor pd : descriptors) {
            Method writeMethod = pd.getWriteMethod();
            if (writeMethod != null) {
                try {
                    writeMethod.setAccessible(true);
                } catch (Exception ignored) {
                }
                writeMethods.put(pd.getName(), writeMethod);
                PropertyTarget target = new PropertyTarget(pd.getName(), pd.getPropertyType(), writeMethod, null);
                registerTargetKeys(pd.getName(), target);
            }
        }

        List<Field> fields = ClassUtils.getFieldsByCache(type);
        for (Field field : fields) {
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            try {
                field.setAccessible(true);
            } catch (Exception ignored) {
            }
            String fieldName = field.getName();
            Method setter = writeMethods.get(fieldName);
            if (setter == null) {
                setter = findChainOrNonStandardSetter(type, fieldName, field.getType());
            }
            PropertyTarget target = new PropertyTarget(fieldName, field.getType(), setter, field);
            registerTargetKeys(fieldName, target);
        }

        if (metadataContext != null) {
            for (String col : metadataContext.columnNames()) {
                String fieldName = metadataContext.fieldByColumn(col);
                Field metaField = metadataContext.field(col);
                PropertyTarget existing = fieldName != null ? mappedTargets.get(fieldName) : null;
                if (existing == null && metaField != null) {
                    existing = new PropertyTarget(
                            metaField.getName(),
                            metaField.getType(),
                            findChainOrNonStandardSetter(type, metaField.getName(), metaField.getType()),
                            metaField);
                    registerTargetKeys(metaField.getName(), existing);
                }
                if (existing != null) {
                    mappedTargets.put(col, existing);
                }
            }
        }

        initColumnPropertyAliases();
        if (this.columnPropertyAlias != null) {
            for (Map.Entry<String, String> entry : this.columnPropertyAlias.entrySet()) {
                PropertyTarget target = mappedTargets.get(entry.getValue());
                if (target != null) {
                    mappedTargets.put(entry.getKey(), target);
                }
            }
        }
    }

    private void registerTargetKeys(String fieldName, PropertyTarget target) {
        mappedTargets.put(fieldName, target);
        String snake = Strings.toUnderlineCase(fieldName);
        if (Strings.isNotBlank(snake)) {
            mappedTargets.putIfAbsent(snake, target);
        }
    }

    private static @Nullable Method findChainOrNonStandardSetter(Class<?> clazz, String fieldName, Class<?> fieldType) {
        if (fieldName == null || fieldName.isEmpty()) {
            return null;
        }
        String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        for (Class<?> cur = clazz; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(fieldType) || wrapPrimitive(paramType).isAssignableFrom(wrapPrimitive(fieldType))) {
                        try {
                            m.setAccessible(true);
                        } catch (Exception ignored) {
                        }
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private void initializeRecord() {
        try {
            this.recordComponents = type.getRecordComponents();
            this.recordComponentIndex = new LinkedCaseInsensitiveMap<>();
            Class<?>[] paramTypes = new Class<?>[recordComponents.length];
            for (int i = 0; i < recordComponents.length; i++) {
                RecordComponent rc = recordComponents[i];
                paramTypes[i] = rc.getType();
                this.recordComponentIndex.put(rc.getName(), i);
            }
            this.recordConstructor = type.getDeclaredConstructor(paramTypes);
            this.recordConstructor.setAccessible(true);
            initColumnPropertyAliases();
        } catch (Exception e) {
            throw new DbException("Failed to initialize record constructor for " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static final Class<? extends java.lang.annotation.Annotation> JSON_PROPERTY_CLASS = resolveJsonPropertyClass();

    @SuppressWarnings("unchecked")
    private static Class<? extends java.lang.annotation.Annotation> resolveJsonPropertyClass() {
        try {
            return (Class<? extends java.lang.annotation.Annotation>) Class.forName("com.fasterxml.jackson.annotation.JsonProperty");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String extractJsonPropertyValue(Field field) {
        if (JSON_PROPERTY_CLASS == null || !field.isAnnotationPresent(JSON_PROPERTY_CLASS)) {
            return null;
        }
        try {
            java.lang.annotation.Annotation ann = field.getAnnotation(JSON_PROPERTY_CLASS);
            Method valueMethod = JSON_PROPERTY_CLASS.getMethod("value");
            Object val = valueMethod.invoke(ann);
            return val instanceof String s ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void initColumnPropertyAliases() {
        try {
            LinkedCaseInsensitiveMap<String> tmp = new LinkedCaseInsensitiveMap<>();
            LinkedCaseInsensitiveMap<String> aliasTmpMap = new LinkedCaseInsensitiveMap<>();
            List<Field> fields = ClassUtils.getFieldsByCache(type);
            for (Field field : fields) {
                String jsonProp = extractJsonPropertyValue(field);
                if (Strings.isNotBlank(jsonProp)) {
                    tmp.put(jsonProp.toLowerCase(), field.getName());
                }
                PersistenceAnnotations.findColumnName(field)
                        .ifPresent(colName -> tmp.put(colName.toLowerCase(), field.getName()));
                if (field.isAnnotationPresent(FieldAlias.class)) {
                    FieldAlias fieldAlias = field.getAnnotation(FieldAlias.class);
                    TypedConverter typeConverter = ClassUtils.newInstance(fieldAlias.converter());
                    fieldTypeConverterMap.put(field.getName(), typeConverter);
                    for (String s : fieldAlias.value()) {
                        aliasTmpMap.put(s.trim(), field.getName());
                    }
                }
            }
            tmp.putAll(aliasTmpMap);
            if (!tmp.isEmpty()) {
                this.columnPropertyAlias = tmp;
            }
        } catch (Exception e) {
            log.error("Get fields for class[{}] error", type, e);
        }
    }

    private static boolean isSimpleValueType(Class<?> clazz) {
        return clazz.isPrimitive()
                || SqlFormatUtils.isPrimitive(clazz)
                || CharSequence.class.isAssignableFrom(clazz)
                || Date.class.isAssignableFrom(clazz)
                || java.time.temporal.Temporal.class.isAssignableFrom(clazz)
                || Blob.class.isAssignableFrom(clazz)
                || Clob.class.isAssignableFrom(clazz)
                || clazz.isEnum()
                || byte[].class == clazz
                || java.util.UUID.class == clazz;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T map(ResultSet row) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("BeanRowMapper#convert start...");
            }
            ResultSetMetaData metaData = row.getMetaData();
            int count = metaData.getColumnCount();
            if (count < 1) {
                return null;
            }
            if (this.isRecord) {
                return mapRecord(row, metaData, count);
            }
            if (this.mappedTargets.isEmpty()) {
                return (T) setSingleField(row);
            }
            Constructor<T> ctor = defaultConstructor != null ? defaultConstructor : type.getDeclaredConstructor();
            ctor.setAccessible(true);
            T bean = ctor.newInstance();
            for (int i = 1; i <= count; i++) {
                String columnName = SqlDataUtils.lookupColumnName(metaData, i);
                if (columnName.contains(Strings.DOT)) {
                    columnName = Strings.subAfter(columnName, '.', true);
                }

                PropertyTarget target = resolvePropertyTarget(columnName);
                if (target == null) {
                    continue;
                }
                Class<?> propertyType = target.type();
                Object value = SqlDataUtils.getResultSetValue(row, i, propertyType);
                if (fieldTypeConverterMap.containsKey(target.name())) {
                    value = fieldTypeConverterMap.get(target.name()).convert(value);
                } else if (value != null && !wrapPrimitive(propertyType).isInstance(value)) {
                    value = ConvertUtils.convert(value, propertyType);
                }
                if (value != null || !propertyType.isPrimitive()) {
                    try {
                        target.write(bean, value);
                    } catch (Exception e) {
                        log.error("Write property[{}] value[{}] type[{}] error:{}", target.name(), value,
                                value == null ? "null" : value.getClass().getName(), e.getMessage(), e);
                        throw new DbException(e);
                    }
                }
            }
            return bean;
        } catch (Exception e) {
            if (e instanceof DbException) {
                throw (DbException) e;
            }
            throw new DbException(e.getMessage(), e);
        }
    }

    private T mapRecord(ResultSet row, ResultSetMetaData metaData, int count) throws Exception {
        Objects.requireNonNull(recordComponents, "recordComponents must not be null");
        Objects.requireNonNull(recordConstructor, "recordConstructor must not be null");
        Objects.requireNonNull(recordComponentIndex, "recordComponentIndex must not be null");

        Object[] args = new Object[recordComponents.length];
        for (int i = 0; i < recordComponents.length; i++) {
            args[i] = defaultPrimitiveValue(recordComponents[i].getType());
        }

        for (int i = 1; i <= count; i++) {
            String columnName = SqlDataUtils.lookupColumnName(metaData, i);
            if (columnName.contains(Strings.DOT)) {
                columnName = Strings.subAfter(columnName, '.', true);
            }
            Integer compIdx = resolveRecordComponentIndex(columnName);
            if (compIdx == null) {
                continue;
            }
            RecordComponent rc = recordComponents[compIdx];
            Class<?> compType = rc.getType();
            Object value = SqlDataUtils.getResultSetValue(row, i, compType);
            if (fieldTypeConverterMap.containsKey(rc.getName())) {
                value = fieldTypeConverterMap.get(rc.getName()).convert(value);
            } else if (value != null && ! wrapPrimitive(compType).isInstance(value)) {
                value = ConvertUtils.convert(value, compType);
            }
            if (value != null || !compType.isPrimitive()) {
                args[compIdx] = value;
            }
        }
        return recordConstructor.newInstance(args);
    }

    private Integer resolveRecordComponentIndex(String columnName) {
        if (recordComponentIndex == null) {
            return null;
        }
        if (CollectionUtils.isNotEmpty(this.columnPropertyAlias)) {
            String aliased = this.columnPropertyAlias.get(columnName);
            if (aliased != null && recordComponentIndex.containsKey(aliased)) {
                return recordComponentIndex.get(aliased);
            }
        }
        if (recordComponentIndex.containsKey(columnName)) {
            return recordComponentIndex.get(columnName);
        }
        if (columnName.contains("_")) {
            String camel = Strings.toCamelCase(columnName);
            if (recordComponentIndex.containsKey(camel)) {
                return recordComponentIndex.get(camel);
            }
        }
        return null;
    }

    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
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
        return type;
    }

    private static Object defaultPrimitiveValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    private @Nullable PropertyTarget resolvePropertyTarget(String columnName) {
        if (columnName == null || this.mappedTargets.isEmpty()) {
            return null;
        }
        PropertyTarget direct = this.mappedTargets.get(columnName);
        if (direct != null) {
            return direct;
        }
        if (columnName.contains("_")) {
            String camel = Strings.toCamelCase(columnName);
            return this.mappedTargets.get(camel);
        }
        return null;
    }

    /**
     * set single value
     *
     * @param resultSet value
     * @return value after transformed
     */
    private Object setSingleField(ResultSet resultSet) {
        return new SingleColumnRowMapper<>(type).map(resultSet);
    }


}
