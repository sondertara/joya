package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SqlRewriter} 的只读上下文。
 *
 * <p>刻意不暴露可变语句对象：改写只能通过返回新 {@code SqlNode} 完成，
 * 这样批量与单条共享同一套语义，也不会出现“改写了一半”的中间态。
 */
public final class SqlRewriteContext {

    private final @Nullable Operation operation;
    private final @Nullable Class<?> entityType;
    private final @Nullable DbType dbType;
    private final Map<String, Object> attributes;

    public SqlRewriteContext(@Nullable Operation operation,
                             @Nullable Class<?> entityType,
                             @Nullable DbType dbType,
                             @Nullable Map<String, Object> attributes) {
        this.operation = operation;
        this.entityType = entityType;
        this.dbType = dbType;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(attributes);
    }

    public @Nullable Operation operation() {
        return operation;
    }

    public @Nullable Class<?> entityType() {
        return entityType;
    }

    public @Nullable DbType dbType() {
        return dbType;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    @SuppressWarnings("unchecked")
    public <V> @Nullable V attribute(String key) {
        return (V) attributes.get(key);
    }

    public static SqlRewriteContext of(@Nullable Operation operation,
                                       @Nullable Class<?> entityType,
                                       @Nullable DbType dbType) {
        return new SqlRewriteContext(operation, entityType, dbType, null);
    }

    public SqlRewriteContext withAttributes(Map<String, Object> newAttributes) {
        return new SqlRewriteContext(operation, entityType, dbType, Objects.requireNonNull(newAttributes));
    }
}
