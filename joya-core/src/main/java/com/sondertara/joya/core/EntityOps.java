package com.sondertara.joya.core;

import com.sondertara.joya.core.metadata.LocalTableMetaCache;
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

    private final ExecutionKernel kernel;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final EntityFinder<T> finder;
    private final EntityWriter<T> writer;
    private final UpsertPlanner<T> upsert;

    EntityOps(ExecutionKernel kernel, Class<T> entityType) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = LocalTableMetaCache.getMetadataContext(entityType);
        this.finder = new EntityFinder<>(kernel, entityType, meta);
        this.writer = new EntityWriter<>(kernel, meta);
        this.upsert = new UpsertPlanner<>(kernel, meta, writer, finder);
    }

    public static <T> EntityOps<T> of(ExecutionKernel kernel, Class<T> entityType) {
        return new EntityOps<>(kernel, entityType);
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

    /**
     * 单条保存：按主键存在则更新、不存在则插入。
     *
     * <p>方言支持原生 UPSERT，且本实体/本表在 {@code SELECT} / {@code INSERT} / {@code UPDATE}
     * 上均无匹配 {@code SqlRewriter} 时走原生路径；否则回退到受行级 {@code WHERE} 保护的先查后写。
     * 回退模式下「跨可见范围」的主键会被视为不可见，最终以唯一键冲突暴露，而不是静默覆盖他人数据。
     */
    public MutationResult save(T entity) {
        return upsert.save(entity, false);
    }

    public MutationResult saveIgnoreNull(T entity) {
        return upsert.save(entity, true);
    }

    /**
     * 批量保存，语义同 {@link #save(Object)}。
     *
     * <p>回退路径按 {@code batchSize} 分片，每片先 SELECT 再 UPDATE/INSERT；自动提交模式下分片之间
     * 跨连接执行，<b>不保证整批原子性</b>。需要整批原子提交时请放在 {@code joya.tx(...)} 内执行。
     */
    public MutationResult saveAll(Collection<T> entities) {
        return upsert.saveAll(entities, false);
    }

    public MutationResult saveAllIgnoreNull(Collection<T> entities) {
        return upsert.saveAll(entities, true);
    }

    public MutationResult deleteById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        String pk = meta.requirePrimaryKey();
        return delete().where(w -> w.eq(pk, id)).execute();
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
        Object pkValue = meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for delete: " + entityType.getName());
        }
        return delete().where(w -> w.eq(pk, pkValue)).execute();
    }

    public MutationResult deleteAll(Collection<? extends T> entities) {
        String pk = meta.requirePrimaryKey();
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
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
            return delete().where(w -> w.in(pk, ids)).execute();
        }
        long total = 0L;
        List<Object> idList = new ArrayList<>(ids);
        for (List<Object> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            total += delete().where(w -> w.in(pk, chunk)).execute().affectedRows();
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
