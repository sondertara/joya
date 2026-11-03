package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 实体删除器 —— 只负责删除与清空，不参与查询与批量写规划。
 *
 * <p>从 {@code EntityOps} 中拆出：删除是独立的写语义，
 * 与"批量插入/更新"的分片、归组策略无关。
 */
final class EntityDeleter<T> {

    private final ExecutionKernel template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final String primaryKey;
    private final EntityLifecycle lifecycle;

    EntityDeleter(ExecutionKernel template, Class<T> entityType, EntityMetadataContext meta, String primaryKey) {
        this(template, entityType, meta, primaryKey, EntityLifecycle.EMPTY);
    }

    EntityDeleter(ExecutionKernel template, Class<T> entityType, EntityMetadataContext meta, String primaryKey,
                  EntityLifecycle lifecycle) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.primaryKey = primaryKey;
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
    }

    MutationResult deleteById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        requirePrimaryKey();
        return new EntityDelete<T>(template, meta).where(w -> w.eq(primaryKey, id)).execute();
    }

    MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        requirePrimaryKey();
        if (ids == null || ids.isEmpty()) {
            return MutationResult.EMPTY;
        }
        if (ids.size() <= Partitions.MAX_IN_CLAUSE_SIZE) {
            return new EntityDelete<T>(template, meta).where(w -> w.in(primaryKey, ids)).execute();
        }
        long total = 0L;
        List<Serializable> idList = new ArrayList<>(ids);
        for (List<Serializable> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            total += new EntityDelete<T>(template, meta)
                    .where(w -> w.in(primaryKey, chunk))
                    .execute()
                    .affectedRows();
        }
        return MutationResult.ofAffected(total);
    }

    MutationResult delete(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        requirePrimaryKey();
        Object pkValue = meta.readFieldValue(entity, primaryKey);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for delete: " + entityType.getName());
        }
        lifecycle.preRemove(entity);
        MutationResult result = new EntityDelete<T>(template, meta).where(w -> w.eq(primaryKey, pkValue)).execute();
        lifecycle.postRemove(entity);
        return result;
    }

    MutationResult deleteAll(Collection<? extends T> entities) {
        requirePrimaryKey();
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<Serializable> ids = new ArrayList<>(entities.size());
        List<T> validEntities = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Objects.requireNonNull(entity, "Entity element in deleteAll must not be null");
            Object pkValue = meta.readFieldValue(entity, primaryKey);
            if (pkValue == null) {
                throw new IllegalArgumentException(
                        "Entity primary key must not be null for deleteAll: " + entityType.getName());
            }
            ids.add(pkValue instanceof Serializable s ? s : pkValue.toString());
            validEntities.add(entity);
        }
        for (T entity : validEntities) {
            lifecycle.preRemove(entity);
        }
        MutationResult result = deleteByIds(ids);
        for (T entity : validEntities) {
            lifecycle.postRemove(entity);
        }
        return result;
    }

    private void requirePrimaryKey() {
        if (primaryKey == null || primaryKey.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + entityType.getName());
        }
    }

    void truncate() {
        template.update(DeleteStatementFactory.createTruncate(meta, template.namingPolicy()));
    }
}
