package com.sondertara.joya.core.metadata;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ClassUtils;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实体元数据上下文 —— 纯只读、不可变、可安全缓存复用。
 * <p>
 * 只描述「这个实体对应什么表」，不持有任何业务数据（values）、不持有 PreparedParameter、
 * 不触发 SQL 生成、不被 Builder 修改。所有访问器返回缓存的不可变视图，构造一次、永久复用
 * （列顺序由构造时的添加顺序或 {@link LocalTableMetaCache} 按类继承层级与字节码字段声明顺序一次性固化，同 JVM 内严格稳定）。
 */
public final class EntityMetadataContext {

    private final Class<?> entityType;
    private final String tableName;
    private final String schemaName;
    private final String primaryKey;
    private final Class<?> primaryKeyType;
    private final List<String> columnNames;                  // 有序、不可变
    private final List<String> fieldNames;                   // 有序、不可变
    private final Map<String, String> columnToField;         // 列名 → 字段名
    private final Map<String, String> lowerColumnToField;    // 小写列名 → 字段名（O(1) 大小写无关）
    private final Map<String, String> lowerColumnToCanonical;// 小写列名 → 规范列名
    private final Map<String, String> fieldToColumn;         // 字段名 → 列名
    private final Map<String, String> lowerFieldToCanonical; // 小写字段名 → 规范字段名
    private final Map<String, Field> columnFields;           // 规范列名 → 预缓存 Field
    private static final MethodType GETTER_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType SETTER_TYPE = MethodType.methodType(void.class, Object.class, Object.class);

    private final Map<String, Integer> columnIndices;        // 规范列名 → 列下标（0..N-1）
    private final @Nullable Field[] indexedFields;           // 按列下标排列的预缓存 Field
    private final @Nullable MethodHandle[] columnGetters;    // 预适配 (Object)Object 的字段读取句柄
    private final @Nullable MethodHandle[] columnSetters;    // 预适配 (Object,Object)void 的字段写入句柄
    private final @Nullable ColumnConverter[] columnConverters; // 按列下标排列的 @Convert 双向转换器
    private final boolean hasConverters;
    private final @Nullable VersionMetadata versionMetadata; // @Version 乐观锁版本列元数据
    private final Set<String> generatedColumns;              // 生成列（自增/数据库生成）
    private final Set<String> nonInsertableColumns;          // @Column(insertable = false)
    private final Set<String> nonUpdatableColumns;           // @Column(updatable = false)
    private final List<String> insertableColumns;
    private final List<String> insertableColumnsWithPrimaryKey;
    private final List<String> updatableColumns;

    private EntityMetadataContext(Builder b) {
        this.entityType = b.entityType;
        this.tableName = b.tableName;
        this.schemaName = b.schemaName;
        this.primaryKey = b.primaryKey;
        this.columnNames = List.copyOf(b.columnNames);
        this.fieldNames = List.copyOf(b.columnToField.values());
        this.columnToField = Map.copyOf(b.columnToField);
        this.fieldToColumn = Map.copyOf(b.fieldToColumn);
        this.generatedColumns = Set.copyOf(b.generatedColumns);
        this.nonInsertableColumns = Set.copyOf(b.nonInsertableColumns);
        this.nonUpdatableColumns = Set.copyOf(b.nonUpdatableColumns);

        Map<String, String> lowerColField = new LinkedHashMap<>();
        Map<String, String> lowerColCanon = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : b.columnToField.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            lowerColField.put(lower, e.getValue());
            lowerColCanon.put(lower, e.getKey());
        }
        this.lowerColumnToField = Collections.unmodifiableMap(lowerColField);
        this.lowerColumnToCanonical = Collections.unmodifiableMap(lowerColCanon);

        Map<String, String> lowerFldCanon = new LinkedHashMap<>();
        for (String f : b.fieldToColumn.keySet()) {
            lowerFldCanon.put(f.toLowerCase(Locale.ROOT), f);
        }
        this.lowerFieldToCanonical = Collections.unmodifiableMap(lowerFldCanon);

        int colSize = this.columnNames.size();
        Map<String, Integer> indices = new LinkedHashMap<>(colSize);
        Map<String, Field> fieldObjMap = new LinkedHashMap<>(colSize);
        Field[] fieldsArr = new Field[colSize];
        MethodHandle[] gettersArr = new MethodHandle[colSize];
        MethodHandle[] settersArr = new MethodHandle[colSize];
        ColumnConverter[] convertersArr = new ColumnConverter[colSize];
        boolean anyConverter = false;
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        for (int i = 0; i < colSize; i++) {
            String col = this.columnNames.get(i);
            indices.put(col, i);
            ColumnConverter conv = b.columnConverters.get(col);
            if (conv != null) {
                convertersArr[i] = conv;
                anyConverter = true;
            }
            String fieldName = this.columnToField.get(col);
            Field f = b.columnFields.get(col);
            if (f == null && b.entityType != null && fieldName != null) {
                f = findDeclaredField(b.entityType, fieldName);
            }
            if (f != null) {
                try {
                    f.setAccessible(true);
                } catch (RuntimeException ignored) {
                }
                fieldObjMap.put(col, f);
                fieldsArr[i] = f;
                gettersArr[i] = compileGetter(lookup, f);
                settersArr[i] = compileSetter(lookup, f);
            }
        }
        this.columnIndices = Collections.unmodifiableMap(indices);
        this.columnFields = Collections.unmodifiableMap(fieldObjMap);
        this.indexedFields = fieldsArr;
        this.columnGetters = gettersArr;
        this.columnSetters = settersArr;
        this.columnConverters = convertersArr;
        this.hasConverters = anyConverter;

        if (b.versionColumn != null) {
            Integer vIdx = indices.get(b.versionColumn);
            if (vIdx == null) {
                throw new DbException("@Version column '" + b.versionColumn + "' is not registered on entity "
                        + (b.entityType != null ? b.entityType.getName() : b.tableName));
            }
            Class<?> vType = b.versionType;
            if (vType == null && fieldsArr[vIdx] != null) {
                vType = fieldsArr[vIdx].getType();
            }
            if (vType == null) {
                vType = Integer.class;
            }
            this.versionMetadata = new VersionMetadata(b.versionColumn, vIdx, vType);
        } else {
            this.versionMetadata = null;
        }

        Class<?> pkType = b.primaryKeyType;
        if (pkType == null && this.primaryKey != null) {
            Field pkField = this.columnFields.get(this.primaryKey);
            if (pkField != null) {
                pkType = pkField.getType();
            }
        }
        this.primaryKeyType = pkType;

        this.insertableColumns = this.columnNames.stream()
                .filter(c -> !this.generatedColumns.contains(c) && !this.nonInsertableColumns.contains(c))
                .toList();
        this.insertableColumnsWithPrimaryKey = this.columnNames.stream()
                .filter(c -> !this.nonInsertableColumns.contains(c)
                        && (c.equals(this.primaryKey) || !this.generatedColumns.contains(c)))
                .toList();
        this.updatableColumns = this.columnNames.stream()
                .filter(c -> !c.equals(this.primaryKey)
                        && !this.generatedColumns.contains(c)
                        && !this.nonUpdatableColumns.contains(c))
                .toList();
    }

    private static @Nullable MethodHandle compileGetter(MethodHandles.Lookup lookup, Field field) {
        try {
            return lookup.unreflectGetter(field).asType(GETTER_TYPE);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static @Nullable MethodHandle compileSetter(MethodHandles.Lookup lookup, Field field) {
        if (Modifier.isFinal(field.getModifiers())) {
            return null;
        }
        try {
            return lookup.unreflectSetter(field).asType(SETTER_TYPE);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static @Nullable Field findDeclaredField(Class<?> type, String fieldName) {
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                return cur.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    // ===== 访问器：全部返回不可变视图 =====

    public Class<?> entityType() {
        return entityType;
    }

    public String tableName() {
        return tableName;
    }

    public String tableName(@Nullable NamingPolicy namingPolicy) {
        if (namingPolicy != null) {
            return namingPolicy.formatTable(tableName);
        }
        return tableName;
    }

    public String schemaName() {
        return schemaName;
    }

    /** 带 schema 的限定表名（如 {@code app.user}），无 schema 时等同 {@link #tableName()}。 */
    public String qualifiedTableName() {
        return qualifiedTableName(null);
    }

    /**
     * 按命名策略解析最终写入 SQL 的限定表名。
     *
     * <p>格式化与动态表名解析统一由 {@link NamingPolicy#resolveTable(String, String)} 完成：
     * 大小写策略先生效，随后交给 {@code TableNameResolver}（默认恒等解析）。
     */
    public String qualifiedTableName(@Nullable NamingPolicy namingPolicy) {
        if (namingPolicy == null) {
            return schemaName == null || schemaName.isBlank() ? tableName : schemaName + "." + tableName;
        }
        return namingPolicy.resolveTable(schemaName, tableName);
    }

    /** 按命名策略格式化列名（默认原样保留 {@code @Column(name)} 注解原文，开启 {@link NamingPolicy#columnUppercase()} 时转为大写）。 */
    public @Nullable String formatColumn(@Nullable String columnName, @Nullable NamingPolicy namingPolicy) {
        if (columnName == null) {
            return null;
        }
        String canonical = canonicalColumn(columnName);
        String target = canonical != null ? canonical : columnName;
        return namingPolicy != null ? namingPolicy.formatColumn(target) : target;
    }

    /** 获取元数中记录的规范列名（保留 {@code @Column(name)} 注解原始大小写）。 */
    public @Nullable String canonicalColumn(String columnName) {
        if (columnName == null) {
            return null;
        }
        if (columnToField.containsKey(columnName)) {
            return columnName;
        }
        return lowerColumnToCanonical.get(columnName.toLowerCase(Locale.ROOT));
    }

    public String primaryKey() {
        return primaryKey;
    }

    /** 获取主键列名；若当前实体未声明主键则抛出 {@link IllegalStateException}。 */
    public String requirePrimaryKey() {
        if (primaryKey == null || primaryKey.isBlank()) {
            String owner = entityType != null ? entityType.getName() : tableName;
            throw new IllegalStateException("Entity has no primary key: " + owner);
        }
        return primaryKey;
    }

    /**
     * 校验非自增主键在插入前已赋值：避免渲染出 {@code INSERT ... (id, ...) VALUES (NULL, ...)}
     * 落到 JDBC 层才报模糊的 {@code NULL not allowed}。
     */
    public void requireAssignedPrimaryKey(@Nullable Object pkValue) {
        if (primaryKey == null || pkValue != null || generatedColumns.contains(primaryKey)) {
            return;
        }
        String owner = entityType != null ? entityType.getName() : tableName;
        throw new IllegalArgumentException("Primary key '" + primaryKey + "' of " + owner
                + " is null and is not annotated with @GeneratedValue; assign it before insert");
    }

    /** 主键的 Java 类型。 */
    public @Nullable Class<?> primaryKeyType() {
        return primaryKeyType;
    }

    /** 有序列名列表（INSERT/UPDATE 列顺序的稳定基础）。 */
    public List<String> columnNames() {
        return columnNames;
    }

    /** 有序字段名列表。 */
    public List<String> fieldNames() {
        return fieldNames;
    }

    public @Nullable String fieldByColumn(String columnName) {
        if (columnName == null) {
            return null;
        }
        String exact = columnToField.get(columnName);
        if (exact != null) {
            return exact;
        }
        return lowerColumnToField.get(columnName.toLowerCase(Locale.ROOT));
    }

    public @Nullable String columnByField(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        String exact = fieldToColumn.get(fieldName);
        if (exact != null) {
            return exact;
        }
        String canonicalField = lowerFieldToCanonical.get(fieldName.toLowerCase(Locale.ROOT));
        return canonicalField != null ? fieldToColumn.get(canonicalField) : null;
    }

    public @Nullable String findFieldIgnoreCase(String name) {
        if (name == null) {
            return null;
        }
        return lowerFieldToCanonical.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean containsColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        return columnToField.containsKey(columnName)
                || lowerColumnToCanonical.containsKey(columnName.toLowerCase(Locale.ROOT));
    }

    public boolean containsField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        return fieldToColumn.containsKey(fieldName)
                || lowerFieldToCanonical.containsKey(fieldName.toLowerCase(Locale.ROOT));
    }

    /** 按字段名或列名获取预缓存且已开启访问权限的 {@link Field}。 */
    public @Nullable Field field(String fieldOrColumn) {
        if (fieldOrColumn == null) {
            return null;
        }
        Field direct = columnFields.get(fieldOrColumn);
        if (direct != null) {
            return direct;
        }
        String canonicalCol = lowerColumnToCanonical.get(fieldOrColumn.toLowerCase(Locale.ROOT));
        if (canonicalCol != null) {
            return columnFields.get(canonicalCol);
        }
        String colByField = columnByField(fieldOrColumn);
        return colByField != null ? columnFields.get(colByField) : null;
    }

    /** 列总数。 */
    public int columnCount() {
        return columnNames.size();
    }

    /** 查询列在 {@link #columnNames()} 中的稳定下标（不存在返回 {@code -1}）。 */
    public int columnIndex(@Nullable String columnName) {
        if (columnName == null) {
            return -1;
        }
        Integer direct = columnIndices.get(columnName);
        if (direct != null) {
            return direct;
        }
        String canonical = lowerColumnToCanonical.get(columnName.toLowerCase(Locale.ROOT));
        if (canonical != null) {
            Integer idx = columnIndices.get(canonical);
            return idx != null ? idx : -1;
        }
        String colByField = columnByField(columnName);
        if (colByField != null) {
            Integer idx = columnIndices.get(colByField);
            return idx != null ? idx : -1;
        }
        return -1;
    }

    /**
     * 按列顺序一次性提取实体所有字段值到紧凑 {@code Object[]}（通过预编译 {@link MethodHandle#invokeExact} 读取，零 {@code Map} 分配）。
     */
    public Object[] snapshotValues(Object entity) {
        int len = columnNames.size();
        Object[] snapshot = new Object[len];
        for (int i = 0; i < len; i++) {
            snapshot[i] = readIndexedValue(entity, i);
        }
        return snapshot;
    }

    /** 从 {@link #snapshotValues(Object)} 产出的紧凑数组中按列名或字段名读取值。 */
    public @Nullable Object columnValue(Object[] snapshot, String columnOrField) {
        int idx = columnIndex(columnOrField);
        return (idx >= 0 && idx < snapshot.length) ? snapshot[idx] : null;
    }

    private @Nullable Object readIndexedValue(Object entity, int index) {
        MethodHandle getter = columnGetters[index];
        if (getter != null) {
            try {
                return (Object) getter.invokeExact(entity);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new DbException("Failed to read column '{}' on {}", columnNames.get(index), entityType.getName(), e);
            }
        }
        Field f = indexedFields[index];
        if (f != null) {
            try {
                return f.get(entity);
            } catch (IllegalAccessException ignored) {
            }
        }
        String fieldName = fieldNames.get(index);
        return fieldName != null ? ClassUtils.getFieldValue(entity, fieldName) : null;
    }

    /**
     * 一次性读取全部可映射字段为有序 Map（供外部快照检视使用；内部写规划统一优先使用零 Map 分配的 {@link #snapshotValues(Object)}）。
     */
    public Map<String, Object> readFieldValues(Object entity) {
        Object[] raw = snapshotValues(entity);
        Map<String, Object> values = new LinkedHashMap<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            values.put(columnNames.get(i), raw[i]);
        }
        return values;
    }

    /** 直接通过预编译 {@link MethodHandle} 读取单个实体列值。 */
    public @Nullable Object readFieldValue(Object entity, String columnName) {
        if (entity == null || columnName == null) {
            return null;
        }
        int idx = columnIndex(columnName);
        if (idx >= 0) {
            return readIndexedValue(entity, idx);
        }
        String fieldName = fieldByColumn(columnName);
        return fieldName != null ? ClassUtils.getFieldValue(entity, fieldName) : null;
    }

    /** 直接通过预编译 {@link MethodHandle} 回写实体属性值。 */
    public void writeFieldValue(Object entity, String columnName, @Nullable Object value) {
        if (entity == null || columnName == null) {
            return;
        }
        int idx = columnIndex(columnName);
        if (idx >= 0) {
            MethodHandle setter = columnSetters[idx];
            Field f = indexedFields[idx];
            if (setter != null && (value != null || (f != null && !f.getType().isPrimitive()))) {
                try {
                    setter.invokeExact(entity, value);
                    return;
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new DbException("Failed to write column '{}' on {}", columnNames.get(idx), entityType.getName(), e);
                }
            }
            if (f != null) {
                try {
                    f.set(entity, value);
                    return;
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        String fieldName = fieldByColumn(columnName);
        if (fieldName != null) {
            ClassUtils.setFieldValue(entity, fieldName, value);
        }
    }

    /** 按 Lambda Getter 引用解析规范列名，不存在时抛出 {@link IllegalArgumentException}。 */
    public <E> String resolveColumn(IGetter<E, ?> getter) {
        String prop = LambdaUtils.resolve(getter).propertyName();
        String col = columnByField(prop);
        if (col == null) {
            throw new IllegalArgumentException("Unknown property '" + prop + "' on entity " + entityType.getName());
        }
        return col;
    }

    /** 按字段名或列名解析规范列名，不存在时抛出 {@link IllegalArgumentException}。 */
    public String resolveColumnName(String columnOrField) {
        if (columnOrField == null || columnOrField.isBlank()) {
            throw new IllegalArgumentException("Column or field name must not be blank");
        }
        String trimmed = columnOrField.trim();
        String col = containsField(trimmed) ? columnByField(trimmed) : canonicalColumn(trimmed);
        if (col != null) {
            return col;
        }
        throw new IllegalArgumentException(
                "Unknown column or field '" + columnOrField + "' for entity " + entityType.getName());
    }

    /** 生成列集合（自增主键、数据库生成列）。 */
    public Set<String> generatedColumns() {
        return generatedColumns;
    }

    /** 不可插入列集合（{@code @Column(insertable = false)}）。 */
    public Set<String> nonInsertableColumns() {
        return nonInsertableColumns;
    }

    /** 不可更新列集合（{@code @Column(updatable = false)}）。 */
    public Set<String> nonUpdatableColumns() {
        return nonUpdatableColumns;
    }

    /** 可插入列：排除生成列与 {@code @Column(insertable = false)} 列。 */
    public List<String> insertableColumns() {
        return insertableColumns;
    }

    /** 包含显式主键的可插入列（当用户手工指定主键值时使用，仍排除 {@code @Column(insertable = false)} 列）。 */
    public List<String> insertableColumnsWithPrimaryKey() {
        return insertableColumnsWithPrimaryKey;
    }

    /** 可更新列：排除生成列与 {@code @Column(updatable = false)} 列（主键通常不可更新，由调用方决定）。 */
    public List<String> updatableColumns() {
        return updatableColumns;
    }

    /** 获取指定列（按规范列名、忽略大小写列名或字段名）关联的 {@link ColumnConverter}。 */
    public @Nullable ColumnConverter converterFor(@Nullable String columnOrField) {
        if (!hasConverters || columnOrField == null) {
            return null;
        }
        int idx = columnIndex(columnOrField);
        return idx >= 0 ? columnConverters[idx] : null;
    }

    /** 按列下标获取 {@link ColumnConverter}（热路径 O(1) 无哈希查找）。 */
    public @Nullable ColumnConverter converterAt(int columnIndex) {
        if (!hasConverters || columnIndex < 0 || columnIndex >= columnConverters.length) {
            return null;
        }
        return columnConverters[columnIndex];
    }

    /** 将实体属性值转换为待写入数据库的列值（无转换器时零开销原样返回）。 */
    public @Nullable Object convertToDatabase(String columnOrField, @Nullable Object attributeValue) {
        ColumnConverter converter = converterFor(columnOrField);
        return converter != null ? converter.toDatabase(attributeValue) : attributeValue;
    }

    /** 弱类型入口按列转换（若调用方已传入数据库列值且非属性类型则透传）。 */
    public @Nullable Object convertToDatabaseIfAttribute(String columnOrField, @Nullable Object value) {
        ColumnConverter converter = converterFor(columnOrField);
        return converter != null ? converter.toDatabaseIfAttribute(value) : value;
    }

    /** 按列下标将实体属性值转换为待写入数据库的列值。 */
    public @Nullable Object convertToDatabaseAt(int columnIndex, @Nullable Object attributeValue) {
        ColumnConverter converter = converterAt(columnIndex);
        return converter != null ? converter.toDatabase(attributeValue) : attributeValue;
    }

    /** 是否声明了 {@code @Version} 乐观锁版本列。 */
    public boolean hasVersion() {
        return versionMetadata != null;
    }

    /** 获取 {@code @Version} 乐观锁版本列元数据；未声明时返回 {@code null}。 */
    public @Nullable VersionMetadata versionMetadata() {
        return versionMetadata;
    }

    /** 校验并返回允许插入的规范列名（统一拒绝非主键生成列，按需拒绝 {@code insertable=false} 列）。 */
    public String requireInsertableColumn(String columnName, boolean rejectNonInsertable) {
        String canonical = canonicalColumn(columnName);
        String checked = canonical != null ? canonical : columnName;
        String owner = entityType != null ? entityType.getName() : tableName;
        if (rejectNonInsertable && nonInsertableColumns.contains(checked)) {
            throw new IllegalArgumentException(
                    "Column '" + checked + "' is marked as insertable=false on entity " + owner);
        }
        if (generatedColumns.contains(checked) && !checked.equals(primaryKey)) {
            throw new IllegalArgumentException(
                    "Column '" + checked + "' is database-generated and cannot be inserted on entity " + owner);
        }
        return checked;
    }

    /** 校验并返回允许更新的规范列名（统一拒绝 {@code updatable=false} 列与数据库生成列）。 */
    public String requireUpdatableColumn(String columnName) {
        String canonical = canonicalColumn(columnName);
        String checked = canonical != null ? canonical : columnName;
        String owner = entityType != null ? entityType.getName() : tableName;
        if (nonUpdatableColumns.contains(checked)) {
            throw new IllegalArgumentException(
                    "Column '" + checked + "' is marked as updatable=false on entity " + owner);
        }
        if (generatedColumns.contains(checked)) {
            throw new IllegalArgumentException(
                    "Column '" + checked + "' is database-generated and cannot be updated on entity " + owner);
        }
        return checked;
    }

    /** 构建器。 */
    public static final class Builder {
        private final Class<?> entityType;
        private String tableName;
        private String schemaName;
        private String primaryKey;
        private Class<?> primaryKeyType;
        private final List<String> columnNames = new ArrayList<>();
        private final Map<String, String> columnToField = new LinkedHashMap<>();
        private final Map<String, String> lowerColumnToField = new LinkedHashMap<>();
        private final Map<String, String> fieldToColumn = new LinkedHashMap<>();
        private final Map<String, Field> columnFields = new LinkedHashMap<>();
        private final Map<String, ColumnConverter> columnConverters = new LinkedHashMap<>();
        private final Set<String> generatedColumns = new LinkedHashSet<>();
        private final Set<String> nonInsertableColumns = new LinkedHashSet<>();
        private final Set<String> nonUpdatableColumns = new LinkedHashSet<>();
        private @Nullable String versionColumn;
        private @Nullable Class<?> versionType;

        public Builder(Class<?> entityType) {
            this.entityType = entityType;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder primaryKey(String primaryKey) {
            if (this.primaryKey != null && !this.primaryKey.equals(primaryKey)) {
                throw new IllegalStateException("Composite primary key (multiple @Id fields: ["
                        + this.primaryKey + ", " + primaryKey + "]) is not supported on entity ["
                        + (entityType != null ? entityType.getName() : "unknown") + "]");
            }
            this.primaryKey = primaryKey;
            return this;
        }

        public Builder primaryKeyType(Class<?> primaryKeyType) {
            this.primaryKeyType = primaryKeyType;
            return this;
        }

        public Builder column(String columnName, String fieldName) {
            return column(columnName, fieldName, null);
        }

        public Builder column(String columnName, String fieldName, @Nullable Field field) {
            String lowered = columnName.toLowerCase(Locale.ROOT);
            String previousField = lowerColumnToField.putIfAbsent(lowered, fieldName);
            if (previousField != null) {
                throw new DbException("Duplicate column name '" + columnName + "' mapped by fields ["
                        + previousField + ", " + fieldName + "] on entity ["
                        + (entityType != null ? entityType.getName() : "unknown") + "]");
            }
            columnToField.put(columnName, fieldName);
            columnNames.add(columnName);
            fieldToColumn.put(fieldName, columnName);
            if (field != null) {
                columnFields.put(columnName, field);
            }
            return this;
        }

        public Builder generatedColumn(String columnName) {
            generatedColumns.add(columnName);
            return this;
        }

        public Builder nonInsertableColumn(String columnName) {
            nonInsertableColumns.add(columnName);
            return this;
        }

        public Builder nonUpdatableColumn(String columnName) {
            nonUpdatableColumns.add(columnName);
            return this;
        }

        public Builder columnConverter(String columnName, ColumnConverter converter) {
            if (converter != null) {
                columnConverters.put(columnName, converter);
            }
            return this;
        }

        public Builder versionColumn(String columnName, Class<?> versionType) {
            if (this.versionColumn != null && !this.versionColumn.equals(columnName)) {
                throw new DbException("Multiple @Version fields ([" + this.versionColumn + ", " + columnName
                        + "]) are not supported on entity ["
                        + (entityType != null ? entityType.getName() : "unknown") + "]");
            }
            this.versionColumn = columnName;
            this.versionType = versionType;
            return this;
        }

        public EntityMetadataContext build() {
            return new EntityMetadataContext(this);
        }
    }
}
