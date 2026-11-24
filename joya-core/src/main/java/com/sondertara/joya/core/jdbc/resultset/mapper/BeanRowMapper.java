package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.metadata.ColumnConverter;
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
import org.jspecify.annotations.Nullable;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将一行数据转换成 JavaBean 或 Java 17 Record：
 * <ul>
 *   <li>全 {@code final} 不可变元数据字段满足 JMM（JLS §17.5）无锁安全发布；</li>
 *   <li>构造器与属性写入 {@link MethodHandle} 在初始化期预适配固定签名，热路径统一走 {@code invokeExact}；</li>
 *   <li>结果集 {@link SQLException} 原样透传至执行内核，统一经 {@code SqlExceptionTranslator} 翻译。</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public class BeanRowMapper<T> implements RowMapper<T> {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodType BEAN_CTOR_TYPE = MethodType.methodType(Object.class);
    private static final MethodType RECORD_SPREAD_CTOR_TYPE = MethodType.methodType(Object.class, Object[].class);
    private static final MethodType PROPERTY_WRITER_TYPE = MethodType.methodType(void.class, Object.class, Object.class);

    @SuppressWarnings("unchecked")
    private static final @Nullable Class<? extends Annotation> JSON_PROPERTY_CLASS = resolveJsonPropertyClass();

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
    private final boolean isRecord;
    private final LinkedCaseInsensitiveMap<PropertyTarget> mappedTargets;
    private final @Nullable LinkedCaseInsensitiveMap<String> columnPropertyAlias;
    private final Map<String, ColumnConverter> fieldTypeConverterMap;
    private final @Nullable Constructor<T> defaultConstructor;
    private final @Nullable MethodHandle defaultConstructorHandle;
    private final @Nullable RecordComponent[] recordComponents;
    private final Object @Nullable [] defaultRecordArgs;
    private final @Nullable Constructor<T> recordConstructor;
    private final @Nullable MethodHandle recordConstructorHandle;
    private final @Nullable LinkedCaseInsensitiveMap<Integer> recordComponentIndex;

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
                    mh = LOOKUP.unreflect(setter).asType(PROPERTY_WRITER_TYPE);
                } catch (IllegalAccessException ignored) {
                }
            }
            if (mh == null && field != null) {
                try {
                    mh = LOOKUP.unreflectSetter(field).asType(PROPERTY_WRITER_TYPE);
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
                writer.invokeExact(bean, value);
            } else if (setter != null) {
                try {
                    setter.invoke(bean, value);
                } catch (InvocationTargetException ite) {
                    throw ite.getTargetException();
                }
            } else if (field != null) {
                field.set(bean, value);
            }
        }
    }

    private record BeanColumnPlan(
            int columnIndex,
            @Nullable String columnClassName,
            PropertyTarget target,
            SqlDataUtils.@Nullable ColumnReader converterReader
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
            boolean converted
    ) {
    }

    private record AliasMetadata(
            @Nullable LinkedCaseInsensitiveMap<String> aliases,
            Map<String, ColumnConverter> converters
    ) {
    }

    public BeanRowMapper(Class<T> type) {
        this.type = type;
        this.isRecord = type != null && type.isRecord();

        if (this.isRecord) {
            this.mappedTargets = new LinkedCaseInsensitiveMap<>(0);
            this.defaultConstructor = null;
            this.defaultConstructorHandle = null;
            try {
                RecordComponent[] components = type.getRecordComponents();
                LinkedCaseInsensitiveMap<Integer> compIndex = new LinkedCaseInsensitiveMap<>(components.length);
                Object[] defaults = new Object[components.length];
                Class<?>[] paramTypes = new Class<?>[components.length];
                for (int i = 0; i < components.length; i++) {
                    RecordComponent rc = components[i];
                    paramTypes[i] = rc.getType();
                    defaults[i] = TypeUtils.defaultValue(rc.getType());
                    compIndex.put(rc.getName(), i);
                }
                Constructor<T> ctor = type.getDeclaredConstructor(paramTypes);
                ctor.setAccessible(true);
                MethodHandle ctorHandle = null;
                try {
                    ctorHandle = LOOKUP.unreflectConstructor(ctor)
                            .asSpreader(Object[].class, components.length)
                            .asType(RECORD_SPREAD_CTOR_TYPE);
                } catch (IllegalAccessException ignored) {
                }
                AliasMetadata aliasMeta = buildColumnPropertyAliases(type);
                this.recordComponents = components;
                this.defaultRecordArgs = defaults;
                this.recordConstructor = ctor;
                this.recordConstructorHandle = ctorHandle;
                this.recordComponentIndex = compIndex;
                this.columnPropertyAlias = aliasMeta.aliases();
                this.fieldTypeConverterMap = aliasMeta.converters();
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw new DbException("Failed to initialize record constructor for " + type.getName(), e);
            }
            return;
        }

        this.recordComponents = null;
        this.defaultRecordArgs = null;
        this.recordConstructor = null;
        this.recordConstructorHandle = null;
        this.recordComponentIndex = null;

        if (type == null || type.isInterface() || isSimpleValueType(type)) {
            this.mappedTargets = new LinkedCaseInsensitiveMap<>(0);
            this.columnPropertyAlias = null;
            this.fieldTypeConverterMap = Map.of();
            this.defaultConstructor = null;
            this.defaultConstructorHandle = null;
            return;
        }

        Constructor<T> beanCtor = null;
        MethodHandle beanCtorHandle = null;
        try {
            beanCtor = type.getDeclaredConstructor();
            beanCtor.setAccessible(true);
            try {
                beanCtorHandle = LOOKUP.unreflectConstructor(beanCtor).asType(BEAN_CTOR_TYPE);
            } catch (IllegalAccessException ignored) {
            }
        } catch (NoSuchMethodException ignored) {
        }
        this.defaultConstructor = beanCtor;
        this.defaultConstructorHandle = beanCtorHandle;

        EntityMetadataContext metadataContext = PersistenceAnnotations.isTableOrEntityPresent(type)
                ? LocalTableMetaCache.getMetadataContext(type)
                : null;

        AliasMetadata aliasMeta = buildColumnPropertyAliases(type);
        Map<String, ColumnConverter> converters = aliasMeta.converters();

        LinkedCaseInsensitiveMap<PropertyTarget> targets = new LinkedCaseInsensitiveMap<>();
        Map<String, Method> writeMethods = new HashMap<>();
        Collection<PropertyDescriptor> descriptors;
        try {
            descriptors = Arrays.asList(Introspector.getBeanInfo(type).getPropertyDescriptors());
        } catch (IntrospectionException e) {
            throw new DbException("Failed to introspect bean: " + type.getName(), e);
        }
        for (PropertyDescriptor pd : descriptors) {
            Method writeMethod = pd.getWriteMethod();
            if (writeMethod != null) {
                if (!TypeUtils.isColumnType(pd.getPropertyType())
                        && !converters.containsKey(pd.getName())) {
                    continue;
                }
                try {
                    writeMethod.setAccessible(true);
                } catch (Exception ignored) {
                }
                writeMethods.put(pd.getName(), writeMethod);
                PropertyTarget target = PropertyTarget.create(pd.getName(), pd.getPropertyType(), writeMethod, null);
                registerTargetKeys(targets, pd.getName(), target);
            }
        }

        List<Field> fields = ClassUtils.getFieldsByCache(type);
        for (Field field : fields) {
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            if (!TypeUtils.isColumnType(field.getType()) && !converters.containsKey(field.getName())) {
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
            registerTargetKeys(targets, fieldName, target);
        }

        if (metadataContext != null) {
            for (String col : metadataContext.columnNames()) {
                String fieldName = metadataContext.fieldByColumn(col);
                Field metaField = metadataContext.field(col);
                PropertyTarget existing = fieldName != null ? targets.get(fieldName) : null;
                if (existing == null && metaField != null) {
                    try {
                        metaField.setAccessible(true);
                    } catch (Exception ignored) {
                    }
                    existing = PropertyTarget.create(
                            metaField.getName(),
                            metaField.getType(),
                            findChainOrNonStandardSetter(type, metaField.getName(), metaField.getType()),
                            metaField);
                    registerTargetKeys(targets, metaField.getName(), existing);
                }
                if (existing != null) {
                    targets.put(col, existing);
                }
            }
        }

        LinkedCaseInsensitiveMap<String> aliases = aliasMeta.aliases();
        if (aliases != null) {
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                PropertyTarget target = targets.get(entry.getValue());
                if (target != null) {
                    targets.put(entry.getKey(), target);
                }
            }
        }

        this.mappedTargets = targets;
        this.columnPropertyAlias = aliases;
        this.fieldTypeConverterMap = converters;
    }

    private static void registerTargetKeys(
            LinkedCaseInsensitiveMap<PropertyTarget> targets,
            String fieldName,
            PropertyTarget target
    ) {
        targets.put(fieldName, target);
        String snake = Strings.toUnderlineCase(fieldName);
        if (Strings.isNotBlank(snake)) {
            targets.putIfAbsent(snake, target);
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
                    if (paramType.isAssignableFrom(fieldType)
                            || TypeUtils.getWrapClass(paramType).isAssignableFrom(TypeUtils.getWrapClass(fieldType))) {
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

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Annotation> resolveJsonPropertyClass() {
        try {
            return (Class<? extends Annotation>) Class.forName("com.fasterxml.jackson.annotation.JsonProperty");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static @Nullable String extractJsonPropertyValue(Field field) {
        if (JSON_PROPERTY_CLASS == null || !field.isAnnotationPresent(JSON_PROPERTY_CLASS)) {
            return null;
        }
        try {
            Annotation ann = field.getAnnotation(JSON_PROPERTY_CLASS);
            Method valueMethod = JSON_PROPERTY_CLASS.getMethod("value");
            Object val = valueMethod.invoke(ann);
            return val instanceof String s ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static AliasMetadata buildColumnPropertyAliases(Class<?> type) {
        LinkedCaseInsensitiveMap<String> tmp = new LinkedCaseInsensitiveMap<>();
        Map<String, ColumnConverter> converters = new HashMap<>();
        List<Field> fields = ClassUtils.getFieldsByCache(type);
        for (Field field : fields) {
            String jsonProp = extractJsonPropertyValue(field);
            if (Strings.isNotBlank(jsonProp)) {
                tmp.put(jsonProp.toLowerCase(Locale.ROOT), field.getName());
            }
            PersistenceAnnotations.findColumnName(field)
                    .ifPresent(colName -> tmp.put(colName.toLowerCase(Locale.ROOT), field.getName()));
            PersistenceAnnotations.findConverter(type, field)
                    .ifPresent(conv -> converters.put(field.getName(), conv));
        }
        return new AliasMetadata(
                tmp.isEmpty() ? null : tmp,
                converters.isEmpty() ? Map.of() : Collections.unmodifiableMap(converters));
    }

    private static SqlDataUtils.ColumnReader converterReader(ColumnConverter converter) {
        Class<?> dbType = converter.databaseColumnType();
        if (dbType == Object.class) {
            return (rs, index, colClassName) ->
                    converter.toEntity(SqlDataUtils.getResultSetValue(rs, index, colClassName));
        }
        Class<?> dbBoxed = TypeUtils.getWrapClass(dbType);
        SqlDataUtils.ColumnReader baseReader = SqlDataUtils.readerFor(dbType);
        return (rs, index, colClassName) -> {
            Object raw = baseReader.read(rs, index, colClassName);
            if (raw != null && !dbBoxed.isInstance(raw)) {
                raw = ConvertUtils.convert(raw, dbType);
            }
            return converter.toEntity(raw);
        };
    }

    private static boolean isSimpleValueType(Class<?> clazz) {
        return clazz.isPrimitive()
                || TypeUtils.isPrimitiveOrNumber(clazz)
                || CharSequence.class.isAssignableFrom(clazz)
                || Date.class.isAssignableFrom(clazz)
                || Temporal.class.isAssignableFrom(clazz)
                || Blob.class.isAssignableFrom(clazz)
                || Clob.class.isAssignableFrom(clazz)
                || clazz.isEnum()
                || byte[].class == clazz
                || UUID.class == clazz;
    }

    /**
     * 派生附带源列读取器覆盖表的轻量 {@link RowMapper} 视图（不修改缓存的不可变 {@link BeanRowMapper} 单例），
     * 使未重复标注 {@code @Convert} 的普通 DTO/Record 也能自动继承源实体的列转换器（含非标量字段如 {@code List<String>}）。
     */
    public RowMapper<T> withColumnReaders(Map<String, SqlDataUtils.ColumnReader> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideMap = new LinkedCaseInsensitiveMap<>(overrides.size());
        overrideMap.putAll(overrides);
        LinkedCaseInsensitiveMap<PropertyTarget> augmentedTargets = this.isRecord
                ? null
                : buildAugmentedTargets(overrideMap);
        return new RowMapper<>() {
            @Override
            public RowMapper<T> forResultSet(ResultSet rs) throws SQLException {
                return BeanRowMapper.this.forResultSetWithOverrides(rs, augmentedTargets, overrideMap);
            }

            @Override
            public T map(ResultSet row) throws SQLException {
                return forResultSet(row).map(row);
            }
        };
    }

    private @Nullable LinkedCaseInsensitiveMap<PropertyTarget> buildAugmentedTargets(
            LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideMap) {
        if (type == null || type.isInterface() || isSimpleValueType(type)) {
            return null;
        }
        LinkedCaseInsensitiveMap<PropertyTarget> augmented = null;
        for (Field field : ClassUtils.getFieldsByCache(type)) {
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            String fieldName = field.getName();
            if (this.mappedTargets.containsKey(fieldName)) {
                continue;
            }
            String snake = Strings.toUnderlineCase(fieldName);
            boolean matched = overrideMap.containsKey(fieldName)
                    || (Strings.isNotBlank(snake) && overrideMap.containsKey(snake));
            if (!matched) {
                continue;
            }
            try {
                field.setAccessible(true);
            } catch (Exception ignored) {
            }
            Method setter = findChainOrNonStandardSetter(type, fieldName, field.getType());
            PropertyTarget target = PropertyTarget.create(fieldName, field.getType(), setter, field);
            if (augmented == null) {
                augmented = new LinkedCaseInsensitiveMap<>(this.mappedTargets.size() + 4);
                augmented.putAll(this.mappedTargets);
            }
            registerTargetKeys(augmented, fieldName, target);
        }
        return augmented;
    }

    @Override
    public RowMapper<T> forResultSet(ResultSet rs) throws SQLException {
        return forResultSetWithOverrides(rs, null, null);
    }

    private RowMapper<T> forResultSetWithOverrides(
            ResultSet rs,
            @Nullable LinkedCaseInsensitiveMap<PropertyTarget> augmentedTargets,
            @Nullable LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideReaders) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int count = metaData.getColumnCount();
        if (count < 1) {
            return row -> null;
        }
        if (this.isRecord) {
            RecordColumnPlan[] plans = buildRecordColumnPlans(metaData, count, overrideReaders);
            return row -> mapRecordWithPlans(row, plans);
        }
        LinkedCaseInsensitiveMap<PropertyTarget> effectiveTargets =
                augmentedTargets != null ? augmentedTargets : this.mappedTargets;
        if (effectiveTargets.isEmpty()) {
            if (!ResultType.isScalar(type)) {
                throw new IllegalStateException("Cannot map result set to " + type.getName()
                        + ": no writable properties; use a concrete DTO/record or listMap() for Map rows");
            }
            return new SingleColumnRowMapper<>(type).forResultSet(rs);
        }
        BeanColumnPlan[] plans = buildBeanColumnPlans(metaData, count, effectiveTargets, overrideReaders);
        return row -> mapBeanWithPlans(row, plans);
    }

    @Override
    public T map(ResultSet row) throws SQLException {
        return forResultSet(row).map(row);
    }

    private BeanColumnPlan[] buildBeanColumnPlans(
            ResultSetMetaData metaData,
            int count,
            LinkedCaseInsensitiveMap<PropertyTarget> targets,
            @Nullable LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideReaders) throws SQLException {
        List<BeanColumnPlan> plans = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String columnName = normalizeColumnName(SqlDataUtils.lookupColumnName(metaData, i));
            PropertyTarget target = resolvePropertyTarget(columnName, targets);
            if (target == null) {
                continue;
            }
            String colClassName = SqlDataUtils.safeGetColumnClassName(metaData, i);
            ColumnConverter converter =
                    fieldTypeConverterMap.isEmpty() ? null : fieldTypeConverterMap.get(target.name());
            SqlDataUtils.ColumnReader readerOverride = converter != null
                    ? converterReader(converter)
                    : lookupOverrideReader(overrideReaders, target.name(), columnName);
            plans.add(new BeanColumnPlan(i, colClassName, target, readerOverride));
        }
        return plans.toArray(new BeanColumnPlan[0]);
    }

    private RecordColumnPlan[] buildRecordColumnPlans(
            ResultSetMetaData metaData,
            int count,
            @Nullable LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideReaders) throws SQLException {
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
            ColumnConverter converter =
                    fieldTypeConverterMap.isEmpty() ? null : fieldTypeConverterMap.get(rc.getName());
            SqlDataUtils.ColumnReader readerOverride = converter != null
                    ? converterReader(converter)
                    : lookupOverrideReader(overrideReaders, rc.getName(), columnName);
            plans.add(new RecordColumnPlan(
                    i,
                    compIdx,
                    compType,
                    TypeUtils.getWrapClass(compType),
                    compType.isPrimitive(),
                    colClassName,
                    readerOverride != null ? readerOverride : SqlDataUtils.readerFor(compType),
                    readerOverride != null));
        }
        return plans.toArray(new RecordColumnPlan[0]);
    }

    private static SqlDataUtils.@Nullable ColumnReader lookupOverrideReader(
            @Nullable LinkedCaseInsensitiveMap<SqlDataUtils.ColumnReader> overrideReaders,
            String propertyName,
            @Nullable String columnName) {
        if (overrideReaders == null || overrideReaders.isEmpty()) {
            return null;
        }
        SqlDataUtils.ColumnReader direct = overrideReaders.get(propertyName);
        if (direct != null) {
            return direct;
        }
        return columnName != null ? overrideReaders.get(columnName) : null;
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
            Object instance = defaultConstructorHandle.invokeExact();
            return (T) instance;
        }
        Constructor<T> ctor = defaultConstructor != null ? defaultConstructor : type.getDeclaredConstructor();
        ctor.setAccessible(true);
        try {
            return ctor.newInstance();
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    private T mapBeanWithPlans(ResultSet row, BeanColumnPlan[] plans) throws SQLException {
        try {
            T bean = instantiateBean();
            Object beanObj = bean;
            for (BeanColumnPlan plan : plans) {
                PropertyTarget target = plan.target();
                Object value;
                if (plan.converterReader() != null) {
                    value = plan.converterReader().read(row, plan.columnIndex(), plan.columnClassName());
                } else {
                    value = target.reader().read(row, plan.columnIndex(), plan.columnClassName());
                    if (value != null && !target.boxedType().isInstance(value)) {
                        value = ConvertUtils.convert(value, target.type());
                    }
                }
                if (value != null || !target.primitive()) {
                    try {
                        target.write(beanObj, value);
                    } catch (Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new DbException("Failed to write property [" + target.name() + "] of "
                                + type.getName() + " with value of type ["
                                + (value == null ? "null" : value.getClass().getName()) + "]: " + e.getMessage(), e);
                    }
                }
            }
            return bean;
        } catch (SQLException | DbException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private T mapRecordWithPlans(ResultSet row, RecordColumnPlan[] plans) throws SQLException {
        try {
            Objects.requireNonNull(recordConstructor, "recordConstructor must not be null");
            Objects.requireNonNull(defaultRecordArgs, "defaultRecordArgs must not be null");
            Object[] args = defaultRecordArgs.clone();
            for (RecordColumnPlan plan : plans) {
                Object value = plan.reader().read(row, plan.columnIndex(), plan.columnClassName());
                if (!plan.converted() && value != null && !plan.boxedType().isInstance(value)) {
                    value = ConvertUtils.convert(value, plan.componentType());
                }
                if (value != null || !plan.primitive()) {
                    args[plan.componentIndex()] = value;
                }
            }
            if (recordConstructorHandle != null) {
                Object instance = recordConstructorHandle.invokeExact(args);
                return (T) instance;
            }
            try {
                return recordConstructor.newInstance(args);
            } catch (InvocationTargetException ite) {
                throw ite.getTargetException();
            }
        } catch (SQLException | DbException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    private @Nullable Integer resolveRecordComponentIndex(String columnName) {
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

    private static @Nullable PropertyTarget resolvePropertyTarget(
            String columnName,
            LinkedCaseInsensitiveMap<PropertyTarget> targets) {
        if (columnName == null || targets.isEmpty()) {
            return null;
        }
        PropertyTarget direct = targets.get(columnName);
        if (direct != null) {
            return direct;
        }
        if (columnName.indexOf('_') >= 0) {
            String camel = Strings.toCamelCase(columnName);
            return targets.get(camel);
        }
        return null;
    }
}
