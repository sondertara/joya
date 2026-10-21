package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.common.function.TypedConverter;
import com.sondertara.common.reflect.ReflectUtils;

import com.sondertara.joya.utils.Strings;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.ClassUtils;
import com.sondertara.joya.utils.PropertyUtils;
import com.sondertara.common.text.VariableStyles;
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

    private LinkedCaseInsensitiveMap<PropertyDescriptor> mappedFields;

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

    public BeanRowMapper(Class<T> type) {
        this.type = type;
        this.isRecord = type != null && type.isRecord();
        initialize();
    }


    private void initialize() {
        this.mappedFields = new LinkedCaseInsensitiveMap<>();
        this.fieldTypeConverterMap = new HashMap<>();
        if (this.isRecord) {
            initializeRecord();
            return;
        }
        try {
            if (type != null && !type.isInterface() && !type.isPrimitive()) {
                this.defaultConstructor = type.getDeclaredConstructor();
                this.defaultConstructor.setAccessible(true);
            }
        } catch (NoSuchMethodException ignored) {
        }
        Collection<PropertyDescriptor> collection = PropertyUtils.getPropertyDescriptors(type);
        collection.forEach(pd -> {
            if (pd.getWriteMethod() != null) {
                this.mappedFields.put(pd.getName(), pd);
            }

        });
        if (PersistenceAnnotations.isTableOrEntityPresent(type)) {
            metadataContext = CacheHolder.tableMetaCache().getMetadataContext(type);
        } else if (!SqlFormatUtils.isPrimitive(type)) {
            initColumnPropertyAliases();
        }
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
                } else if (field.isAnnotationPresent(FieldAlias.class)) {
                    FieldAlias fieldAlias = field.getAnnotation(FieldAlias.class);
                    TypedConverter typeConverter = ReflectUtils.newInstance(fieldAlias.converter());
                    fieldTypeConverterMap.put(field.getName(), typeConverter);
                    for (String s : fieldAlias.value()) {
                        aliasTmpMap.put(s.trim(), field.getName());
                    }

                } else {
                    PersistenceAnnotations.findColumnName(field)
                            .ifPresent(colName -> tmp.put(colName.toLowerCase(), field.getName()));
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
            if (Blob.class == type) {
                return (T) row.getBlob(1);
            } else if (Clob.class == type) {
                return (T) row.getClob(1);
            }

            if (Date.class.isAssignableFrom(type)) {
                return (T) row.getDate(1);
            }
            if (this.isRecord) {
                return mapRecord(row, metaData, count);
            }
            if (this.mappedFields.isEmpty()) {
                return (T) setSingleField(row);
            }
            T bean = (defaultConstructor != null ? defaultConstructor : type.getDeclaredConstructor()).newInstance();
            for (int i = 1; i <= count; i++) {
                String columnName = SqlDataUtils.lookupColumnName(metaData, i);
                //if the name has alias
                if (columnName.contains(Strings.DOT)) {
                    columnName = Strings.subAfter(columnName, '.', true);
                }

                //not property found for bean field
                String bestGuessProperty = bestGuessProperty(columnName);
                if (null == bestGuessProperty) {
                    continue;
                }
                PropertyDescriptor pd = this.mappedFields.get(bestGuessProperty);
                if (pd == null) {
                    continue;
                }
                Class<?> propertyType = pd.getPropertyType();
                Object value = SqlDataUtils.getResultSetValue(row, i, propertyType);
                if (fieldTypeConverterMap.containsKey(pd.getName())) {
                    value = fieldTypeConverterMap.get(pd.getName()).convert(value);
                }
                Method setter = pd.getWriteMethod();
                if (value != null && null != setter) {
                    try {
                        setter.invoke(bean, value);
                    } catch (Exception e) {
                        log.error("Invoke method[{}] value[{}] type[{}] error:{}", setter.getName(), value, value.getClass().getName(), e.getMessage(), e);
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
            String camel = VariableStyles.SNAKE.toCamelCase(columnName);
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

    private String bestGuessProperty(String aliasName) {
        String propertyName = null;
        // 1. 判断是否为 entity（O(1) 大小写无关查找）
        if (null != metadataContext) {
            propertyName = metadataContext.fieldByColumn(aliasName);
            if (null == propertyName) {
                propertyName = metadataContext.findFieldIgnoreCase(aliasName);
            }
        }
        //2.判断普通 pojo是否设置别名
        if (null == propertyName && CollectionUtils.isNotEmpty(this.columnPropertyAlias)) {
            propertyName = this.columnPropertyAlias.get(aliasName);
            if (null == propertyName) {
                for (Map.Entry<String, String> entry : this.columnPropertyAlias.entrySet()) {
                    if (aliasName.equalsIgnoreCase(entry.getKey()) || aliasName.equalsIgnoreCase(entry.getValue())) {
                        propertyName = entry.getValue();
                        break;
                    }
                }
            }
        }
        //3.普通pojo按驼峰转换
        if (null == propertyName) {
            propertyName = aliasName;
            if (aliasName.contains(String.valueOf("_"))) {
                propertyName = VariableStyles.SNAKE.toCamelCase(aliasName);
            }
        }
        if (this.mappedFields.containsKey(propertyName)) {
            return propertyName;
        }
        //3.1模糊匹配
        for (Map.Entry<String, PropertyDescriptor> entry : this.mappedFields.entrySet()) {
            if (propertyName.equalsIgnoreCase(entry.getKey())) {
                propertyName = entry.getKey();
                break;
            }
        }
        return propertyName;
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
