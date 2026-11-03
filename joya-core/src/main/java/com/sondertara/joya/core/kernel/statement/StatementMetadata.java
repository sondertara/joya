package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 语句级元数据，承载 {@link SqlStatement} 的附加信息，避免在顶层继续堆积可选字段。
 *
 * @param operation  操作类型
 * @param dbType     目标数据库类型
 * @param tables     语句涉及的物理表引用（分片/租户拦截器依赖此字段）
 * @param entityType 实体类型（实体 CRUD 语句填充，裸 SQL 为 null）
 * @param primaryKey 主键列名（实体 INSERT 语句填充，用于多列 GeneratedKeys 精确定位主键列）
 */
public record StatementMetadata(
        Operation operation,
        DbType dbType,
        Set<TableRef> tables,
        Class<?> entityType,
        @Nullable String primaryKey
) {

    public StatementMetadata {
        tables = tables == null ? Collections.emptySet() : Set.copyOf(tables);
    }

    public StatementMetadata(
            Operation operation,
            DbType dbType,
            Set<TableRef> tables,
            Class<?> entityType
    ) {
        this(operation, dbType, tables, entityType, null);
    }

    /** 便捷构造：仅含操作类型。 */
    public static StatementMetadata of(Operation operation) {
        return new StatementMetadata(operation, null, Collections.emptySet(), null, null);
    }

    /** 便捷构造：操作类型 + 数据库类型。 */
    public static StatementMetadata of(Operation operation, DbType dbType) {
        return new StatementMetadata(operation, dbType, Collections.emptySet(), null, null);
    }

    /** 便捷构造：操作类型 + 表引用集合。 */
    public static StatementMetadata of(Operation operation, Set<TableRef> tables) {
        return new StatementMetadata(operation, null, tables, null, null);
    }

    /** 便捷构造：操作类型 + 实体类型。 */
    public static StatementMetadata of(Operation operation, Class<?> entityType) {
        return new StatementMetadata(operation, null, Collections.emptySet(), entityType, null);
    }

    /** 带表引用的副本。 */
    public StatementMetadata withTables(Set<TableRef> tables) {
        return new StatementMetadata(operation, dbType, tables, entityType, primaryKey);
    }

    /** 带实体类型的副本。 */
    public StatementMetadata withEntityType(Class<?> type) {
        return new StatementMetadata(operation, dbType, tables, type, primaryKey);
    }

    /** 带数据库类型的副本。 */
    public StatementMetadata withDbType(DbType type) {
        return new StatementMetadata(operation, type, tables, entityType, primaryKey);
    }

    /** 空元数据（裸 SQL 逃生舱使用）。 */
    public static StatementMetadata empty() {
        return new StatementMetadata(null, null, Collections.emptySet(), null, null);
    }

    /** 构建器风格入口。 */
    public static Builder builder(Operation operation) {
        return new Builder(operation);
    }

    /** 元数据构建器。 */
    public static final class Builder {
        private final Operation operation;
        private DbType dbType;
        private final Set<TableRef> tables = new LinkedHashSet<>();
        private Class<?> entityType;
        private @Nullable String primaryKey;

        private Builder(Operation operation) {
            this.operation = operation;
        }

        public Builder dbType(DbType dbType) {
            this.dbType = dbType;
            return this;
        }

        public Builder table(TableRef table) {
            this.tables.add(table);
            return this;
        }

        public Builder tables(Set<TableRef> tables) {
            this.tables.addAll(tables);
            return this;
        }

        public Builder entityType(Class<?> entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder primaryKey(@Nullable String primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public StatementMetadata build() {
            return new StatementMetadata(operation, dbType, tables, entityType, primaryKey);
        }
    }
}
