package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 更新计划：按主键更新（实体快照）与显式条件更新（builder）共用的唯一执行入口。
 *
 * <p>此前 {@code EntityUpdate.execute()} 与批量单条入口各自构造
 * {@link MutationContext} 并调用 {@link UpdateStatementFactory}，监听器时机与列选择逻辑分散；
 * 现在两者只负责选择计划模式，SQL 构建与执行只在这里发生。
 */
final class UpdatePlan<T> {

    private final EntityMetadataContext meta;
    private final Map<String, Object> values;
    private final @Nullable WhereCriterion where;
    private final Set<String> declared;
    private final boolean ignoreNull;
    private final boolean allowAll;
    private final @Nullable T sourceEntity;
    private final EntityLifecycle lifecycle;

    private UpdatePlan(EntityMetadataContext meta,
                       Map<String, Object> values,
                       @Nullable WhereCriterion where,
                       Set<String> declared,
                       boolean ignoreNull,
                       boolean allowAll,
                       @Nullable T sourceEntity,
                       EntityLifecycle lifecycle) {
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.values = Objects.requireNonNull(values, "values must not be null");
        this.where = where;
        this.declared = Objects.requireNonNull(declared, "declared columns must not be null");
        this.ignoreNull = ignoreNull;
        this.allowAll = allowAll;
        this.sourceEntity = sourceEntity;
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
    }

    /**
     * 按实体主键更新：先触发 {@code preUpdate} 与主键校验，再对实体做快照。
     */
    static <T> UpdatePlan<T> byPrimaryKey(EntityMetadataContext meta, T entity, boolean ignoreNull,
                                          String pk, EntityLifecycle lifecycle) {
        Objects.requireNonNull(entity, "entity must not be null");
        EntityLifecycle effectiveLifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
        effectiveLifecycle.preUpdate(entity);
        Object pkValue = meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for update: " + meta.entityType().getName());
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, entity).values();
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        return new UpdatePlan<>(meta, values, WhereCriterion.create().eq(pk, pkValue),
                new LinkedHashSet<>(setColumns), ignoreNull, false, entity, effectiveLifecycle);
    }

    /**
     * 显式条件更新：值与 WHERE 由调用方提供，不触发实体监听器。
     */
    static UpdatePlan<Void> explicit(EntityMetadataContext meta, Map<String, Object> values,
                                     @Nullable WhereCriterion where, boolean allowAll) {
        return new UpdatePlan<>(meta, values, where, new LinkedHashSet<>(values.keySet()),
                false, allowAll, null, EntityLifecycle.EMPTY);
    }

    SqlStatement toSqlStatement(ExecutionKernel kernel) {
        MutationContext ctx = new MutationContext(meta, values, where, declared, ignoreNull, allowAll,
                kernel.namingPolicy());
        return UpdateStatementFactory.create(ctx);
    }

    MutationResult execute(ExecutionKernel kernel) {
        long affected = kernel.update(toSqlStatement(kernel));
        if (sourceEntity != null) {
            lifecycle.postUpdate(sourceEntity);
        }
        return MutationResult.ofAffected(affected);
    }

    // ===== 批量更新（收敛到 UpdatePlan） =====

    /** 一次实体快照：批量更新/局部更新共用，避免同一行反复反射读取。 */
    private record UpdateRowPlan<E>(E entity, Map<String, Object> values, Object pkValue) {
    }

    static <T> MutationResult executeUpdateAll(ExecutionKernel kernel,
                                               EntityMetadataContext meta,
                                               Collection<T> entities,
                                               List<String> setColumns,
                                               String pk,
                                               EntityLifecycle lifecycle) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        EntityLifecycle effectiveLifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
        List<UpdateRowPlan<T>> plans = new ArrayList<>(entities.size());
        for (T row : entities) {
            plans.add(prepareUpdateRow(meta, row, pk, effectiveLifecycle));
        }
        MutationResult result = executeBatch(kernel, buildUpdateBatch(kernel, meta, plans, setColumns, pk));
        for (UpdateRowPlan<T> plan : plans) {
            effectiveLifecycle.postUpdate(plan.entity());
        }
        return result;
    }

    static <T> MutationResult executeUpdateAllByColumns(ExecutionKernel kernel,
                                                        EntityMetadataContext meta,
                                                        Collection<T> entities,
                                                        Collection<String> columns,
                                                        String pk,
                                                        EntityLifecycle lifecycle) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column must be specified for selective updateAll");
        }
        Set<String> uniqueCols = new LinkedHashSet<>();
        for (String colOrField : columns) {
            String resolved = meta.resolveColumnName(colOrField);
            if (meta.nonUpdatableColumns().contains(resolved)) {
                throw new IllegalArgumentException(
                        "Column '" + resolved + "' is marked as updatable=false on entity "
                                + meta.entityType().getName());
            }
            if (!resolved.equals(pk)) {
                uniqueCols.add(resolved);
            }
        }
        if (uniqueCols.isEmpty()) {
            throw new IllegalArgumentException("Update columns must contain at least one non-primary-key column");
        }
        return executeUpdateAll(kernel, meta, entities, new ArrayList<>(uniqueCols), pk, lifecycle);
    }

    static <T> MutationResult executePatchAll(ExecutionKernel kernel,
                                              EntityMetadataContext meta,
                                              Collection<T> entities,
                                              String pk,
                                              EntityLifecycle lifecycle) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        EntityLifecycle effectiveLifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
        record UpdateRun<E>(List<String> cols, List<UpdateRowPlan<E>> rows) {
        }
        List<String> candidateCols = new ArrayList<>(meta.updatableColumns());
        candidateCols.remove(pk);
        List<UpdateRun<T>> runs = new ArrayList<>();
        for (T row : entities) {
            UpdateRowPlan<T> plan = prepareUpdateRow(meta, row, pk, effectiveLifecycle);
            List<String> nonNullCols = new ArrayList<>(candidateCols.size());
            for (String col : candidateCols) {
                if (plan.values().get(col) != null) {
                    nonNullCols.add(col);
                }
            }
            if (nonNullCols.isEmpty()) {
                throw new IllegalStateException("UPDATE statement has no columns to set");
            }
            List<String> cols = List.copyOf(nonNullCols);
            if (!runs.isEmpty() && runs.get(runs.size() - 1).cols().equals(cols)) {
                runs.get(runs.size() - 1).rows().add(plan);
            } else {
                List<UpdateRowPlan<T>> rows = new ArrayList<>();
                rows.add(plan);
                runs.add(new UpdateRun<>(cols, rows));
            }
        }
        MutationResult result = MutationResult.EMPTY;
        for (UpdateRun<T> run : runs) {
            result = result.merge(executeBatch(
                    kernel, buildUpdateBatch(kernel, meta, run.rows(), run.cols(), pk)));
            for (UpdateRowPlan<T> plan : run.rows()) {
                effectiveLifecycle.postUpdate(plan.entity());
            }
        }
        return result;
    }

    private static <T> BatchSqlStatement buildUpdateBatch(ExecutionKernel kernel,
                                                          EntityMetadataContext meta,
                                                          List<UpdateRowPlan<T>> plans,
                                                          List<String> setColumns,
                                                          String pk) {
        if (plans == null || plans.isEmpty()) {
            return null;
        }
        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        List<Object[]> rows = new ArrayList<>(plans.size());
        for (UpdateRowPlan<T> plan : plans) {
            Object[] values = new Object[setColumns.size() + 1];
            for (int i = 0; i < setColumns.size(); i++) {
                values[i] = plan.values().get(setColumns.get(i));
            }
            values[setColumns.size()] = plan.pkValue();
            rows.add(values);
        }
        return UpdateStatementFactory.createBatchByPrimaryKey(
                meta,
                setColumns,
                pk,
                rows,
                kernel.namingPolicy(),
                effectiveBatchSize(kernel));
    }

    private static MutationResult executeBatch(ExecutionKernel kernel, BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return MutationResult.EMPTY;
        }
        BatchSlice slice = kernel.batchExecute(batch);
        return slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    private static <T> UpdateRowPlan<T> prepareUpdateRow(EntityMetadataContext meta, T row, String pk,
                                                         EntityLifecycle lifecycle) {
        Objects.requireNonNull(row, "Entity element in updateAll/patchAll must not be null");
        lifecycle.preUpdate(row);
        Object pkVal = meta.readFieldValue(row, pk);
        if (pkVal == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for batch update: " + meta.entityType().getName());
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, row).values();
        return new UpdateRowPlan<>(row, values, pkVal);
    }

    private static int effectiveBatchSize(ExecutionKernel kernel) {
        int configured = kernel.executionOptions().batchSize();
        return configured > 0 ? configured : ExecutionOptions.DEFAULT_BATCH_SIZE;
    }
}
