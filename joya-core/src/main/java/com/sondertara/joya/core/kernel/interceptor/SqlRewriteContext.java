package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.TableRef;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

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
    private final Set<TableRef> tables;
    private final @Nullable String rootAlias;
    private final boolean batch;
    private final Map<String, Object> attributes;

    public SqlRewriteContext(@Nullable Operation operation,
                             @Nullable Class<?> entityType,
                             @Nullable DbType dbType,
                             @Nullable Map<String, Object> attributes) {
        this(operation, entityType, dbType, Set.of(), null, false, attributes);
    }

    public SqlRewriteContext(@Nullable Operation operation,
                             @Nullable Class<?> entityType,
                             @Nullable DbType dbType,
                             Set<TableRef> tables,
                             @Nullable String rootAlias,
                             boolean batch,
                             @Nullable Map<String, Object> attributes) {
        this.operation = operation;
        this.entityType = entityType;
        this.dbType = dbType;
        this.tables = tables == null ? Set.of() : Set.copyOf(tables);
        this.rootAlias = rootAlias;
        this.batch = batch;
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

    /** 本次语句涉及的物理表引用。 */
    public Set<TableRef> tables() {
        return tables;
    }

    /** SELECT 根表别名；UPDATE/DELETE/INSERT 为 {@code null}。 */
    public @Nullable String rootAlias() {
        return rootAlias;
    }

    /** 是否批量模板探针。 */
    public boolean isBatch() {
        return batch;
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
}
