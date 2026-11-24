package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.query.pagination.SortCondition;
import com.sondertara.joya.core.result.MutationResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 类型化实体域门面 —— 由 {@link DataOps#of(Class)} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityOps<T> {

    private final ExecutionKernel kernel;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final EntityFinder<T> finder;
    private final UpsertPlanner<T> upsert;

    EntityOps(ExecutionKernel kernel, Class<T> entityType) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = LocalTableMetaCache.getMetadataContext(entityType);
        this.finder = new EntityFinder<>(kernel, entityType, meta);
        this.upsert = new UpsertPlanner<>(kernel, meta, finder);
    }

    public static <T> EntityOps<T> of(ExecutionKernel kernel, Class<T> entityType) {
        return new EntityOps<>(kernel, entityType);
    }

    public MutationResult insert(T entity) {
        return new InsertPlan<>(meta, List.of(Objects.requireNonNull(entity, "entity must not be null")),
                false, "entity must not be null", Map.of()).execute(kernel);
    }

    public MutationResult insertIgnoreNull(T entity) {
        return new InsertPlan<>(meta, List.of(Objects.requireNonNull(entity, "entity must not be null")),
                true, "entity must not be null", Map.of()).execute(kernel);
    }

    public MutationResult insertAll(Collection<T> entities) {
        return new InsertPlan<>(meta, entities, false,
                "Entity element in insertAll must not be null", Map.of()).execute(kernel);
    }

    public MutationResult insertAllIgnoreNull(Collection<T> entities) {
        return new InsertPlan<>(meta, entities, true,
                "Entity element in insertAllIgnoreNull must not be null", Map.of()).execute(kernel);
    }

    public MutationResult update(T entity) {
        return UpdatePlan.executeByPrimaryKey(kernel, meta, entity, false);
    }

    public MutationResult patch(T entity) {
        return UpdatePlan.executeByPrimaryKey(kernel, meta, entity, true);
    }

    public MutationResult updateAll(Collection<T> entities) {
        String pk = meta.requirePrimaryKey();
        return UpdatePlan.executeUpdateAll(kernel, meta, entities, meta.updatableColumns(), pk);
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
        return UpdatePlan.executeUpdateAllByColumns(kernel, meta, entities, columns, meta.requirePrimaryKey());
    }

    public MutationResult patchAll(Collection<T> entities) {
        return UpdatePlan.executePatchAll(kernel, meta, entities, meta.requirePrimaryKey());
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
        Objects.requireNonNull(id, "id must not be null");
        String pk = meta.requirePrimaryKey();
        return delete().where(w -> w.eqTrusted(pk, id)).execute();
    }

    public MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        String pk = meta.requirePrimaryKey();
        if (ids == null || ids.isEmpty()) {
            return MutationResult.EMPTY;
        }
        return deleteByRawIds(pk, ids);
    }

    public MutationResult delete(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.requirePrimaryKey();
        if (meta.hasVersion()) {
            return EntityDelete.deleteVersioned(kernel, meta, pk, entity);
        }
        Object pkValue = meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for delete: " + entityType.getName());
        }
        return delete().where(w -> w.eqTrusted(pk, pkValue)).execute();
    }

    public MutationResult deleteAll(Collection<? extends T> entities) {
        String pk = meta.requirePrimaryKey();
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        if (meta.hasVersion()) {
            return EntityDelete.deleteAllVersioned(kernel, meta, pk, entities);
        }
        List<Object> ids = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Objects.requireNonNull(entity, "Entity element in deleteAll must not be null");
            Object pkValue = meta.readFieldValue(entity, pk);
            if (pkValue == null) {
                throw new IllegalArgumentException(
                        "Entity primary key must not be null for deleteAll: " + entityType.getName());
            }
            ids.add(pkValue);
        }
        return deleteByRawIds(pk, ids);
    }

    private MutationResult deleteByRawIds(String pk, Collection<?> ids) {
        for (Object id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("Entity id element in deleteByIds/deleteAll must not be null");
            }
        }
        if (ids.size() <= Partitions.MAX_IN_CLAUSE_SIZE) {
            return delete().where(w -> w.inTrusted(pk, ids)).execute();
        }
        long total = 0L;
        List<Object> idList = new ArrayList<>(ids);
        for (List<Object> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            total += delete().where(w -> w.inTrusted(pk, chunk)).execute().affectedRows();
        }
        return MutationResult.ofAffected(total);
    }

    public void truncate() {
        EntityDelete.truncate(kernel, meta);
    }

    public EntityInsert<T> insert() {
        return new EntityInsert<>(kernel, meta);
    }

    public EntityUpdate<T> update() {
        return new EntityUpdate<>(kernel, meta);
    }

    public EntityDelete<T> delete() {
        return new EntityDelete<>(kernel, meta);
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
