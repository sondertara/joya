package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.query.pagination.SortCondition;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 实体读取器 —— 只负责"按主键/集合/条件读"，不参与任何写入。
 *
 * <p>独立读取器：批量写规划器不应出现 {@code find} 语义。
 */
final class EntityFinder<T> {

    private final ExecutionKernel kernel;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;

    EntityFinder(ExecutionKernel kernel, Class<T> entityType, EntityMetadataContext meta) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    Optional<T> findById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        String pk = meta.requirePrimaryKey();
        return new Query<>(kernel, entityType)
                .where(w -> w.eq(pk, id))
                .one();
    }

    List<T> findByIds(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String pk = meta.requirePrimaryKey();
        for (Serializable id : ids) {
            if (id == null) {
                throw new IllegalArgumentException("Entity id element in findByIds must not be null");
            }
        }
        if (ids.size() <= Partitions.MAX_IN_CLAUSE_SIZE) {
            return queryByIds(pk, ids);
        }
        List<T> result = new ArrayList<>(ids.size());
        List<Serializable> idList = new ArrayList<>(ids);
        for (List<Serializable> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            result.addAll(queryByIds(pk, chunk));
        }
        return result;
    }

    List<T> findAll() {
        return new Query<>(kernel, entityType).list();
    }

    List<T> findAll(SortCondition... sorts) {
        return new Query<>(kernel, entityType).orderBy(sorts).list();
    }

    boolean existsById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        String pk = meta.requirePrimaryKey();
        return new Query<>(kernel, entityType).where(w -> w.eq(pk, id)).exists();
    }

    long count() {
        return new Query<>(kernel, entityType).count();
    }

    private List<T> queryByIds(String pk, Collection<? extends Serializable> ids) {
        return new Query<>(kernel, entityType).where(w -> w.in(pk, ids)).list();
    }
}
