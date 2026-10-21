package com.sondertara.joya.core.metadata;

import com.sondertara.joya.core.model.EntityTableMetaDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体元数据上下文 —— 纯只读、不可变、可安全缓存复用。
 * <p>
 * 只描述「这个实体对应什么表」，不持有任何业务数据（values）、不持有 PreparedParameter、
 * 不触发 SQL 生成、不被 Builder 修改。所有访问器返回缓存的不可变视图，构造一次、永久复用。
 *
 * <p>第一阶段包装现有 {@link EntityTableMetaDefinition}，后续逐步将可变 Definition
 * 转为不可变元数据对象。
 */
public final class EntityMetadataContext {

    private final Class<?> entityType;
    private final String tableName;
    private final String schemaName;
    private final String primaryKey;
    private final List<String> columnNames;          // 有序、不可变
    private final Map<String, String> columnToField; // 列名 → 字段名
    private final Map<String, String> fieldToColumn; // 字段名 → 列名
    private final Set<String> generatedColumns;      // 生成列（自增/数据库生成）
    private final EntityTableMetaDefinition definition; // 底层 Definition（仅取主键类型等）

    private EntityMetadataContext(Builder b) {
        this.entityType = b.entityType;
        this.tableName = b.tableName;
        this.schemaName = b.schemaName;
        this.primaryKey = b.primaryKey;
        this.columnNames = List.copyOf(b.columnNames);
        this.columnToField = Map.copyOf(b.columnToField);
        this.fieldToColumn = Map.copyOf(b.fieldToColumn);
        this.generatedColumns = Set.copyOf(b.generatedColumns);
        this.definition = b.definition;
    }

    public static EntityMetadataContext from(EntityTableMetaDefinition def) {
        Builder b = new Builder(def.getEntityClass())
                .definition(def)
                .tableName(def.getTableName())
                .schemaName(def.getSchemaName())
                .primaryKey(def.getPrimaryKey());
        for (String col : def.getColumnNames()) {
            String field = def.findFieldNameByColumn(col);
            b.column(col, field);
        }
        // 生成列：主键视为生成列（自增），后续可扩展 @Generated 注解识别
        if (def.getPrimaryKey() != null) {
            b.generatedColumn(def.getPrimaryKey());
        }
        return b.build();
    }

    // ===== 访问器：全部返回不可变视图 =====

    public Class<?> entityType() {
        return entityType;
    }

    public String tableName() {
        return tableName;
    }

    public String schemaName() {
        return schemaName;
    }

    /** 带 schema 的限定表名（如 {@code app.user}），无 schema 时等同 {@link #tableName()}。 */
    public String qualifiedTableName() {
        return schemaName == null || schemaName.isBlank()
                ? tableName
                : schemaName + "." + tableName;
    }

    public String primaryKey() {
        return primaryKey;
    }

    /** 主键的 Java 类型（来自底层 Definition）。 */
    public Class<?> primaryKeyType() {
        return definition.getPrimaryKeyType();
    }

    /** 有序列名列表（INSERT/UPDATE 列顺序的稳定基础）。 */
    public List<String> columnNames() {
        return columnNames;
    }

    public String fieldByColumn(String columnName) {
        return columnToField.get(columnName);
    }

    public String columnByField(String fieldName) {
        return fieldToColumn.get(fieldName);
    }

    public boolean containsColumn(String columnName) {
        return columnToField.containsKey(columnName);
    }

    public boolean containsField(String fieldName) {
        return fieldToColumn.containsKey(fieldName);
    }

    /** 生成列集合（自增主键、数据库生成列）。 */
    public Set<String> generatedColumns() {
        return generatedColumns;
    }

    /** 可插入列：排除生成列。 */
    public List<String> insertableColumns() {
        return columnNames.stream()
                .filter(c -> !generatedColumns.contains(c))
                .toList();
    }

    /** 可更新列：排除生成列（主键通常不可更新，由调用方决定）。 */
    public List<String> updatableColumns() {
        return columnNames.stream()
                .filter(c -> !generatedColumns.contains(c))
                .toList();
    }

    /** 构建器。 */
    public static final class Builder {
        private final Class<?> entityType;
        private String tableName;
        private String schemaName;
        private String primaryKey;
        private final List<String> columnNames = new java.util.ArrayList<>();
        private final Map<String, String> columnToField = new java.util.LinkedHashMap<>();
        private final Map<String, String> fieldToColumn = new java.util.LinkedHashMap<>();
        private final Set<String> generatedColumns = new java.util.LinkedHashSet<>();
        private EntityTableMetaDefinition definition;

        public Builder(Class<?> entityType) {
            this.entityType = entityType;
        }

        public Builder definition(EntityTableMetaDefinition definition) {
            this.definition = definition;
            return this;
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

        public Builder column(String columnName, String fieldName) {
            columnNames.add(columnName);
            columnToField.put(columnName, fieldName);
            fieldToColumn.put(fieldName, columnName);
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
