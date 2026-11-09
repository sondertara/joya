package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.result.MutationResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实体写操作执行器（包内）：把"实体 → 写计划 → 执行"收敛在一处，供 {@link EntityOps} 与 {@link UpsertPlanner} 共用，
 * 使 upsert 规划不必回调门面。
 */
final class EntityWriter<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final EntityLifecycle lifecycle;

    EntityWriter(ExecutionKernel kernel, EntityMetadataContext meta, EntityLifecycle lifecycle) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
    }

    MutationResult insert(T entity, boolean ignoreNull) {
        return new InsertPlan<>(meta, List.of(Objects.requireNonNull(entity, "entity must not be null")),
                ignoreNull, "entity must not be null", Map.of(), lifecycle).execute(kernel);
    }

    MutationResult insertAll(Collection<T> entities, boolean ignoreNull) {
        String message = ignoreNull
                ? "Entity element in insertAllIgnoreNull must not be null"
                : "Entity element in insertAll must not be null";
        return new InsertPlan<>(meta, entities, ignoreNull, message, Map.of(), lifecycle).execute(kernel);
    }

    MutationResult updateByPrimaryKey(T entity, boolean ignoreNull) {
        return UpdatePlan.byPrimaryKey(meta, entity, ignoreNull, requirePrimaryKey(), lifecycle).execute(kernel);
    }

    MutationResult updateAll(Collection<T> entities) {
        String pk = requirePrimaryKey();
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        return UpdatePlan.executeUpdateAll(kernel, meta, entities, setColumns, pk, lifecycle);
    }

    MutationResult updateAllByColumns(Collection<T> entities, Collection<String> columns) {
        return UpdatePlan.executeUpdateAllByColumns(kernel, meta, entities, columns, requirePrimaryKey(), lifecycle);
    }

    MutationResult patchAll(Collection<T> entities) {
        return UpdatePlan.executePatchAll(kernel, meta, entities, requirePrimaryKey(), lifecycle);
    }

    String requirePrimaryKey() {
        String pk = meta.primaryKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + meta.entityType().getName());
        }
        return pk;
    }
}
