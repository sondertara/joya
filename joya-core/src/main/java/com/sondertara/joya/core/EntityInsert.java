package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实体链式插入构建器 —— 由 {@link EntityOps#insert()} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityInsert<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final EntityLifecycle lifecycle;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Object> explicitValues = new LinkedHashMap<>();
    private @Nullable T sourceEntity;
    private boolean ignoreNull;

    EntityInsert(ExecutionKernel kernel, EntityMetadataContext meta, EntityLifecycle lifecycle) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
    }

    /** 按 Lambda 属性引用设置列值。 */
    public <V> EntityInsert<T> value(IGetter<T, V> column, @Nullable V value) {
        String colName = meta.resolveColumn(column);
        if (meta.nonInsertableColumns().contains(colName)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is marked as insertable=false on entity " + meta.entityType().getName());
        }
        values.put(colName, value);
        explicitValues.put(colName, value);
        return this;
    }

    /** 按列名或字段名设置列值。 */
    public EntityInsert<T> value(String columnOrField, @Nullable Object value) {
        String colName = meta.resolveColumnName(columnOrField);
        if (meta.nonInsertableColumns().contains(colName)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is marked as insertable=false on entity " + meta.entityType().getName());
        }
        values.put(colName, value);
        explicitValues.put(colName, value);
        return this;
    }

    /** 批量设置列值。 */
    public EntityInsert<T> values(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::value);
        return this;
    }

    /**
     * 从实体快照提取列值（若主键为 null 则自动跳过自增主键列，且遵守 insertable=false）。
     *
     * <p>该方法只负责构建期快照；{@code prePersist} 在 {@link #execute()} 时触发，
     * 触发后会重新快照并重新选列，因此监听器新增/修改的字段（含生成主键）不会丢失。
     */
    public EntityInsert<T> values(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        this.sourceEntity = entity;
        EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, entity);
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : snapshot.value(pk);
        for (String col : InsertPlan.candidateColumns(meta, pkVal)) {
            values.put(col, snapshot.value(col));
        }
        return this;
    }

    /** 忽略值为 null 的列（交由数据库默认值填充）。 */
    public EntityInsert<T> ignoreNulls() {
        this.ignoreNull = true;
        return this;
    }

    /**
     * 渲染为不可变 {@link SqlStatement}。
     *
     * <p>这是纯渲染方法，不触发 {@code prePersist}；实体模式下它反映的是构建期快照。
     * 需要包含监听器处理结果时，请以 {@link #execute()} 为准。
     */
    public SqlStatement toSqlStatement() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Insert values must not be empty");
        }
        Set<String> declared = new LinkedHashSet<>(values.keySet());
        MutationContext ctx = new MutationContext(meta, values, null, declared, ignoreNull, false, kernel.namingPolicy());
        return InsertStatementFactory.create(ctx);
    }

    /** 执行插入并返回包含生成键与真实影响行数的 {@link MutationResult}。 */
    public MutationResult execute() {
        if (sourceEntity != null) {
            // 实体模式统一交给 InsertPlan：prePersist 之后重新快照并重新选列，
            // 监听器新增的字段（含生成主键）因此不会被漏掉。
            return new InsertPlan<>(meta, List.of(sourceEntity), ignoreNull,
                    "entity must not be null", explicitValues, lifecycle).execute(kernel);
        }
        SqlStatement stmt = toSqlStatement();
        String pk = meta.primaryKey();
        Object existingPkVal = pk == null ? null : values.get(pk);
        return InsertPlan.executeSingleStatement(kernel, meta, stmt, null, existingPkVal, false);
    }
}
