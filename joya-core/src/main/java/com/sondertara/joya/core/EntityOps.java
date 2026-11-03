package com.sondertara.joya.core;

import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.pagination.SortCondition;
import com.sondertara.joya.core.result.MutationResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 类型化实体域门面 —— 由 {@link DataOps#of(Class)} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityOps<T> {

    private final ExecutionKernel template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final EntityLifecycle lifecycle;
    private final EntityFinder<T> finder;
    private final EntityDeleter<T> deleter;
    private final EntityWriter<T> writer;
    private final UpsertPlanner<T> upsert;

    EntityOps(ExecutionKernel template, Class<T> entityType) {
        this(template, entityType, EntityLifecycle.EMPTY);
    }

    EntityOps(ExecutionKernel template, Class<T> entityType, EntityLifecycle lifecycle) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = LocalTableMetaCache.getMetadataContext(entityType);
        this.finder = new EntityFinder<>(template, entityType, meta, meta.primaryKey());
        EntityLifecycle effectiveLifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
        this.lifecycle = effectiveLifecycle;
        this.deleter = new EntityDeleter<>(template, entityType, meta, meta.primaryKey(), effectiveLifecycle);
        this.writer = new EntityWriter<>(template, meta, effectiveLifecycle);
        this.upsert = new UpsertPlanner<>(template, meta, effectiveLifecycle, writer, finder);
    }

    public static <T> EntityOps<T> of(ExecutionKernel template, Class<T> entityType, EntityLifecycle lifecycle) {
        return new EntityOps<>(template, entityType, lifecycle);
    }

    public MutationResult insert(T entity) {
        return writer.insert(entity, false);
    }

    public MutationResult insertIgnoreNull(T entity) {
        return writer.insert(entity, true);
    }

    public MutationResult insertAll(Collection<T> entities) {
        return writer.insertAll(entities, false);
    }

    public MutationResult insertAllIgnoreNull(Collection<T> entities) {
        return writer.insertAll(entities, true);
    }

    public MutationResult update(T entity) {
        return writer.updateByPrimaryKey(entity, false);
    }

    public MutationResult patch(T entity) {
        return writer.updateByPrimaryKey(entity, true);
    }

    public MutationResult updateAll(Collection<T> entities) {
        return writer.updateAll(entities);
    }

    @SafeVarargs
    public final MutationResult updateAll(Collection<T> entities, IGetter<T, ?>... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("At least one column must be specified for selective updateAll");
        }
        List<String> colNames = new ArrayList<>(columns.length);
        for (IGetter<T, ?> getter : columns) {
            colNames.add(meta.resolveColumn(Objects.requireNonNull(getter, "column getter must not be null")));
        }
        return updateAllByColumns(entities, colNames);
    }

    public MutationResult updateAllByColumns(Collection<T> entities, Collection<String> columns) {
        return writer.updateAllByColumns(entities, columns);
    }

    public MutationResult patchAll(Collection<T> entities) {
        return writer.patchAll(entities);
    }

    public MutationResult save(T entity) {
        return upsert.save(entity, false);
    }

    public MutationResult saveIgnoreNull(T entity) {
        return upsert.save(entity, true);
    }

    public MutationResult saveAll(Collection<T> entities) {
        return upsert.saveAll(entities, false);
    }

    public MutationResult saveAllIgnoreNull(Collection<T> entities) {
        return upsert.saveAll(entities, true);
    }

    public MutationResult deleteById(Serializable id) {
        return deleter.deleteById(id);
    }

    public MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        return deleter.deleteByIds(ids);
    }

    public MutationResult delete(T entity) {
        return deleter.delete(entity);
    }

    public MutationResult deleteAll(Collection<? extends T> entities) {
        return deleter.deleteAll(entities);
    }

    public void truncate() {
        deleter.truncate();
    }

    public EntityInsert<T> insert() {
        return new EntityInsert<>(template, meta, lifecycle);
    }

    public EntityUpdate<T> update() {
        return new EntityUpdate<>(template, meta);
    }

    public EntityDelete<T> delete() {
        return new EntityDelete<>(template, meta);
    }

    public Optional<T> findById(Serializable id) {
        return finder.findById(id);
    }

    public List<T> findByIds(Collection<? extends Serializable> ids) {
        return finder.findByIds(ids);
    }

    public List<T> findAll() {
        return finder.findAll();
    }

    public List<T> findAll(SortCondition... sorts) {
        return finder.findAll(sorts);
    }

    public boolean existsById(Serializable id) {
        return finder.existsById(id);
    }

    public long count() {
        return finder.count();
    }
}
