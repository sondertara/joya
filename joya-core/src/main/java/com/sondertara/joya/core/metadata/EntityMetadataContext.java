package com.sondertara.joya.core.metadata;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.utils.ClassUtils;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
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
 * 不触发 SQL 生成、不被 Builder 修改。所有访问器返回缓存的不可变视图，构造一次、永久复用。
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
    private final Set<String> generatedColumns;              // 生成列（自增/数据库生成）
    private final List<String> insertableColumns;
    private final List<String> updatableColumns;

    private EntityMetadataContext(Builder b) {
        this.entityType = b.entityType;
        this.tableName = b.tableName;
        this.schemaName = b.schemaName;
        this.primaryKey = b.primaryKey;
        this.columnNames = List.copyOf(b.columnNames);
        this.fieldNames = List.copyOf(b. columnToField.values());
        this.columnToField = Map.copyOf(b.columnToField);
        this.fieldToColumn = Map.copyOf(b.fieldToColumn);
        this.generatedColumns = Set.copyOf(b.generatedColumns);

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

        Map<String, Field> fieldObjMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : b.columnToField.entrySet()) {
            String col = e.getKey();
            Field f = b.columnFields.get(col);
            if (f == null && b.entityType != null) {
                f = findDeclaredField(b.entityType, e.getValue());
            }
            if (f != null) {
                try {
                    f.setAccessible(true);
                } catch (Exception ignored) {
                }
                fieldObjMap.put(col, f);
            }
        }
        this.columnFields = Collections.unmodifiableMap(fieldObjMap);

        Class<?> pkType = b.primaryKeyType;
        if (pkType == null && this.primaryKey != null) {
            Field pkField = this.columnFields.get(this.primaryKey);
            if (pkField != null) {
                pkType = pkField.getType();
            }
        }
        this.primaryKeyType = pkType;

        this.insertableColumns = this.columnNames.stream()
                .filter(c -> !this.generatedColumns.contains(c))
                .toList();
        this.updatableColumns = this.columnNames.stream()
                .filter(c -> !this.generatedColumns.contains(c))
                .toList();
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

    /** 按命名策略格式化后的限定表名（支持 {@link NamingPolicy#tableUppercase()}）。 */
    public String qualifiedTableName(@Nullable NamingPolicy namingPolicy) {
        String tbl = tableName(namingPolicy);
        if (schemaName == null || schemaName.isBlank()) {
            return tbl;
        }
        String sch = namingPolicy != null
                ? namingPolicy.formatTable(schemaName)
                : schemaName;
        return sch + "." + tbl;
    }

    /** 按命名策略格式化列名（默认原样保留 {@code @Column(name)} 注解原文，开启 {@link NamingPolicy#columnUppercase()} 时转为大写）。 */
    public String formatColumn(String columnName, @Nullable NamingPolicy namingPolicy) {
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

    /** 直接通过预缓存的 {@link Field} 读取实体属性值（零二次按名查找）。 */
    public @Nullable Object readFieldValue(Object entity, String columnName) {
        if (entity == null || columnName == null) {
            return null;
        }
        Field f = field(columnName);
        if (f != null) {
            try {
                return f.get(entity);
            } catch (IllegalAccessException ignored) {
            }
        }
        String fieldName = fieldByColumn(columnName);
        return fieldName != null ? ClassUtils.getFieldValue(entity, fieldName) : null;
    }

    /** 直接通过预缓存的 {@link Field} 写入实体属性值。 */
    public void writeFieldValue(Object entity, String columnName, @Nullable Object value) {
        if (entity == null || columnName == null) {
            return;
        }
        Field f = field(columnName);
        if (f != null) {
            try {
                f.set(entity, value);
                return;
            } catch (IllegalAccessException ignored) {
            }
        }
        String fieldName = fieldByColumn(columnName);
        if (fieldName != null) {
            ClassUtils.setFieldValue(entity, fieldName, value);
        }
    }

    /** 按 Lambda Getter 引用解析规范列名，不存在时抛出 {@link IllegalArgumentException}。 */
    public <E> String resolveColumn(IGetter<E, ?> getter) {
        String m = LambdaUtils.getImplMethodName(getter);
        String prop = m.startsWith("get") && m.length() > 3 ? Strings.lowerFirst(m.substring(3))
                : (m.startsWith("is") && m.length() > 2 ? Strings.lowerFirst(m.substring(2)) : m);
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

    /** 可插入列：排除生成列。 */
    public List<String> insertableColumns() {
        return insertableColumns;
    }

    /** 可更新列：排除生成列（主键通常不可更新，由调用方决定）。 */
    public List<String> updatableColumns() {
        return updatableColumns;
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
        private final Map<String, String> fieldToColumn = new LinkedHashMap<>();
        private final Map<String, Field> columnFields = new LinkedHashMap<>();
        private final Set<String> generatedColumns = new LinkedHashSet<>();

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
            columnNames.add(columnName);
            columnToField.put(columnName, fieldName);
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

        public EntityMetadataContext build() {
            return new EntityMetadataContext(this);
        }
    }
}
