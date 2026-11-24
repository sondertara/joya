package com.sondertara.joya.exceptions;

import org.jspecify.annotations.Nullable;

/**
 * 乐观锁冲突异常：实体携带 {@code @Version} 执行更新或删除时，数据库受影响行数为 0（记录已被并发修改或已删除）。
 */
public class OptimisticLockException extends DbException {

    private final @Nullable Class<?> entityType;
    private final @Nullable Object primaryKey;
    private final @Nullable Object expectedVersion;

    public OptimisticLockException(@Nullable Class<?> entityType,
                                   @Nullable Object primaryKey,
                                   @Nullable Object expectedVersion,
                                   String message) {
        super(message);
        this.entityType = entityType;
        this.primaryKey = primaryKey;
        this.expectedVersion = expectedVersion;
    }

    public static OptimisticLockException of(@Nullable Class<?> entityType,
                                             @Nullable Object primaryKey,
                                             @Nullable Object expectedVersion,
                                             String operation) {
        String entityName = entityType != null ? entityType.getName() : "unknown";
        String msg = "Optimistic lock conflict on " + operation + " for entity [" + entityName
                + "] (id=" + primaryKey + ", expectedVersion=" + expectedVersion + ")";
        return new OptimisticLockException(entityType, primaryKey, expectedVersion, msg);
    }

    public @Nullable Class<?> entityType() {
        return entityType;
    }

    public @Nullable Object primaryKey() {
        return primaryKey;
    }

    public @Nullable Object expectedVersion() {
        return expectedVersion;
    }
}
