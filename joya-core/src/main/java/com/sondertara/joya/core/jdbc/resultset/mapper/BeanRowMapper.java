package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.function.TypedConverter;
import com.sondertara.joya.core.function.FieldAlias;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ClassUtils;
import com.sondertara.joya.utils.CollectionUtils;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.utils.TypeUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.beans.PropertyDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将一行数据转换成 JavaBean 或 Java 17 Record（支持 {@link #forResultSet(ResultSet)} 列布局预编译与 {@link MethodHandle} 属性写入）。
 *
 * @author huangxiaohu
 */
@Slf4j
public class BeanRowMapper<T> implements RowMapper<T> {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

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

    private Map<String, TypedConverter<Object, Object>> fieldTypeConverterMap;

    @Nullable
    private EntityMetadataContext metadataContext;

    @Nullable
    private Constructor<T> defaultConstructor;
    @Nullable
    private MethodHandle defaultConstructorHandle;

    private final boolean isRecord;
    @Nullable
    private RecordComponent[] recordComponents;
    private Object[] defaultRecordArgs;
    @Nullable
    private Constructor<T> recordConstructor;
    @Nullable
    private MethodHandle recordConstructorHandle;
    @Nullable
    private LinkedCaseInsensitiveMap<Integer> recordComponentIndex;

    private record PropertyTarget(
            String name,
            Class<?> type,
            Class<?> boxedType,
            boolean primitive,
            SqlDataUtils.ColumnReader reader,
            @Nullable MethodHandle writer,
            @Nullable Method setter,
            @Nullable Field field
    ) {
        static PropertyTarget create(String name, Class<?> type, @Nullable Method setter, @Nullable Field field) {
            MethodHandle mh = null;
            if (setter != null) {
                try {
                    mh = LOOKUP.unreflect(setter);
                } catch (IllegalAccessException ignored) {
                }
            }
            if (mh == null && field != null) {
                try {
                    mh = LOOKUP.unreflectSetter(field);
                } catch (IllegalAccessException ignored) {
                }
            }
            return new PropertyTarget(
                    name,
                    type,
                    TypeUtils.getWrapClass(type),
                    type.isPrimitive(),
                    SqlDataUtils.readerFor(type),
                    mh,
                    setter,
                    field);
        }

        void write(Object bean, Object value) throws Throwable {
            if (writer != null) {
                writer.invoke(bean, value);
            } else if (setter != null) {
                setter.invoke(bean, value);
            } else if (field != null) {
                field.set(bean, value);
            }
        }
    }

    private record BeanColumnPlan(
            int columnIndex,
            @Nullable String columnClassName,
            PropertyTarget target,
            @Nullable TypedConverter<Object, Object> converter
    ) {
    }

    private record RecordColumnPlan(
            int columnIndex,
            int componentIndex,
            Class<?> componentType,
            Class<?> boxedType,
            boolean primitive,
            @Nullable String columnClassName,
            SqlDataUtils.ColumnReader reader,
            @Nullable TypedConverter<Object, Object> converter
    ) {
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
            try {
                this.defaultConstructorHandle = LOOKUP.unreflectConstructor(this.defaultConstructor);
            } catch (IllegalAccessException ignored) {
            }
        } catch (NoSuchMethodException ignored) {
        }
        if (PersistenceAnnotations.isTableOrEntityPresent(type)) {
            metadataContext = LocalTableMetaCache.getMetadataContext(type);
        }

        Map<String, Method> writeMethods = new HashMap<>();
        Collection<PropertyDescriptor> descriptors;
        try {
            descriptors = java.util.Arrays.asList(java.beans.Introspector.getBeanInfo(type).getPropertyDescriptors());
        } catch (java.beans.IntrospectionException e) {
            throw new DbException("Failed to introspect bean: " + type.getName(), e);
        }
        for (PropertyDescriptor pd : descriptors) {
            Method writeMethod = pd.getWriteMethod();
            if (writeMethod != null) {
                if (!TypeUtils.isColumnType(pd.getPropertyType())
                        && !writeMethod.isAnnotationPresent(FieldAlias.class)) {
                    continue;
                }
                try {
                    writeMethod.setAccessible(true);
                } catch (Exception ignored) {
                }
                writeMethods.put(pd.getName(), writeMethod);
                PropertyTarget target = PropertyTarget.create(pd.getName(), pd.getPropertyType(), writeMethod, null);
                registerTargetKeys(pd.getName(), target);
            }
        }

        List<Field> fields = ClassUtils.getFieldsByCache(type);
        for (Field field : fields) {
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            if (!TypeUtils.isColumnType(field.getType()) && !field.isAnnotationPresent(FieldAlias.class)) {
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
            PropertyTarget target = PropertyTarget.create(fieldName, field.getType(), setter, field);
            registerTargetKeys(fieldName, target);
        }

        if (metadataContext != null) {
            for (String col : metadataContext.columnNames()) {
                String fieldName = metadataContext.fieldByColumn(col);
                Field metaField = metadataContext.field(col);
                PropertyTarget existing = fieldName != null ? mappedTargets.get(fieldName) : null;
                if (existing == null && metaField != null) {
                    existing = PropertyTarget.create(
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
                    if (paramType.isAssignableFrom(fieldType) || TypeUtils.getWrapClass(paramType).isAssignableFrom(TypeUtils.getWrapClass(fieldType))) {
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
            this.defaultRecordArgs = new Object[recordComponents.length];
            Class<?>[] paramTypes = new Class<?>[recordComponents.length];
            for (int i = 0; i < recordComponents.length; i++) {
                RecordComponent rc = recordComponents[i];
                paramTypes[i] = rc.getType();
                this.defaultRecordArgs[i] = TypeUtils.defaultValue(rc.getType());
                this.recordComponentIndex.put(rc.getName(), i);
            }
            this.recordConstructor = type.getDeclaredConstructor(paramTypes);
            this.recordConstructor.setAccessible(true);
            try {
                this.recordConstructorHandle = LOOKUP.unreflectConstructor(this.recordConstructor);
            } catch (IllegalAccessException ignored) {
            }
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
                    @SuppressWarnings("rawtypes")
                    Class<? extends TypedConverter> converterClass = fieldAlias.converter();
                    if (converterClass != null && converterClass != TypedConverter.Noop.class) {
                        @SuppressWarnings("unchecked")
                        TypedConverter<Object, Object> typeConverter =
                                (TypedConverter<Object, Object>) ClassUtils.newInstance(converterClass);
                        fieldTypeConverterMap.put(field.getName(), typeConverter);
                    }
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
                || TypeUtils.isPrimitiveOrNumber(clazz)
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
    public RowMapper<T> forResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int count = metaData.getColumnCount();
        if (count < 1) {
            return row -> null;
        }
        if (this.isRecord) {
            RecordColumnPlan[] plans = buildRecordColumnPlans(metaData, count);
            return row -> mapRecordWithPlans(row, plans);
        }
        if (this.mappedTargets.isEmpty()) {
            if (!ResultType.isScalar(type)) {
                throw new IllegalStateException("Cannot map result set to " + type.getName()
                        + ": no writable properties; use a concrete DTO/record or listMap() for Map rows");
            }
            SingleColumnRowMapper<T> scalarMapper = new SingleColumnRowMapper<>(type);
            return scalarMapper::map;
        }
        BeanColumnPlan[] plans = buildBeanColumnPlans(metaData, count);
        return row -> mapBeanWithPlans(row, plans);
    }

    @Override
    public T map(ResultSet row) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("BeanRowMapper#convert start...");
            }
            return forResultSet(row).map(row);
        } catch (DbException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    private BeanColumnPlan[] buildBeanColumnPlans(ResultSetMetaData metaData, int count) throws SQLException {
        List<BeanColumnPlan> plans = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String columnName = normalizeColumnName(SqlDataUtils.lookupColumnName(metaData, i));
            PropertyTarget target = resolvePropertyTarget(columnName);
            if (target == null) {
                continue;
            }
            String colClassName = SqlDataUtils.safeGetColumnClassName(metaData, i);
            TypedConverter<Object, Object> converter =
                    fieldTypeConverterMap.isEmpty() ? null : fieldTypeConverterMap.get(target.name());
            plans.add(new BeanColumnPlan(i, colClassName, target, converter));
        }
        return plans.toArray(new BeanColumnPlan[0]);
    }

    private RecordColumnPlan[] buildRecordColumnPlans(ResultSetMetaData metaData, int count) throws SQLException {
        Objects.requireNonNull(recordComponents, "recordComponents must not be null");
        List<RecordColumnPlan> plans = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String columnName = normalizeColumnName(SqlDataUtils.lookupColumnName(metaData, i));
            Integer compIdx = resolveRecordComponentIndex(columnName);
            if (compIdx == null) {
                continue;
            }
            RecordComponent rc = recordComponents[compIdx];
            Class<?> compType = rc.getType();
            String colClassName = SqlDataUtils.safeGetColumnClassName(metaData, i);
            TypedConverter<Object, Object> converter =
                    fieldTypeConverterMap.isEmpty() ? null : fieldTypeConverterMap.get(rc.getName());
            plans.add(new RecordColumnPlan(
                    i,
                    compIdx,
                    compType,
                    TypeUtils.getWrapClass(compType),
                    compType.isPrimitive(),
                    colClassName,
                    SqlDataUtils.readerFor(compType),
                    converter));
        }
        return plans.toArray(new RecordColumnPlan[0]);
    }

    private static String normalizeColumnName(String columnName) {
        if (columnName != null && columnName.indexOf('.') >= 0) {
            return Strings.subAfter(columnName, '.', true);
        }
        return columnName;
    }

    @SuppressWarnings("unchecked")
    private T instantiateBean() throws Throwable {
        if (defaultConstructorHandle != null) {
            return (T) defaultConstructorHandle.invoke();
        }
        Constructor<T> ctor = defaultConstructor != null ? defaultConstructor : type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private T mapBeanWithPlans(ResultSet row, BeanColumnPlan[] plans) {
        try {
            T bean = instantiateBean();
            for (BeanColumnPlan plan : plans) {
                PropertyTarget target = plan.target();
                Object value;
                if (plan.converter() != null) {
                    Object rawValue = SqlDataUtils.getResultSetValue(row, plan.columnIndex(), plan.columnClassName());
                    value = plan.converter().convert(rawValue);
                } else {
                    value = target.reader().read(row, plan.columnIndex(), plan.columnClassName());
                    if (value != null && !target.boxedType().isInstance(value)) {
                        value = ConvertUtils.convert(value, target.type());
                    }
                }
                if (value != null || !target.primitive()) {
                    try {
                        target.write(bean, value);
                    } catch (Error e) {
                        throw e;
                    } catch (Throwable e) {
                        log.error("Write property[{}] value[{}] type[{}] error:{}", target.name(), value,
                                value == null ? "null" : value.getClass().getName(), e.getMessage(), e);
                        throw new DbException(e);
                    }
                }
            }
            return bean;
        } catch (DbException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private T mapRecordWithPlans(ResultSet row, RecordColumnPlan[] plans) {
        try {
            Objects.requireNonNull(recordConstructor, "recordConstructor must not be null");
            Object[] args = defaultRecordArgs.clone();
            for (RecordColumnPlan plan : plans) {
                Object value;
                if (plan.converter() != null) {
                    Object rawValue = SqlDataUtils.getResultSetValue(row, plan.columnIndex(), plan.columnClassName());
                    value = plan.converter().convert(rawValue);
                } else {
                    value = plan.reader().read(row, plan.columnIndex(), plan.columnClassName());
                    if (value != null && !plan.boxedType().isInstance(value)) {
                        value = ConvertUtils.convert(value, plan.componentType());
                    }
                }
                if (value != null || !plan.primitive()) {
                    args[plan.componentIndex()] = value;
                }
            }
            if (recordConstructorHandle != null) {
                return (T) recordConstructorHandle.invokeWithArguments(args);
            }
            return recordConstructor.newInstance(args);
        } catch (DbException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new DbException(e.getMessage(), e);
        }
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
        if (columnName != null && columnName.indexOf('_') >= 0) {
            String camel = Strings.toCamelCase(columnName);
            if (recordComponentIndex.containsKey(camel)) {
                return recordComponentIndex.get(camel);
            }
        }
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
        if (columnName.indexOf('_') >= 0) {
            String camel = Strings.toCamelCase(columnName);
            return this.mappedTargets.get(camel);
        }
        return null;
    }
}
