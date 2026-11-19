package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityListener;
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

    private UpdatePlan(EntityMetadataContext meta,
                       Map<String, Object> values,
                       @Nullable WhereCriterion where,
                       Set<String> declared,
                       boolean ignoreNull,
                       boolean allowAll) {
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.values = Objects.requireNonNull(values, "values must not be null");
        this.where = where;
        this.declared = Objects.requireNonNull(declared, "declared columns must not be null");
        this.ignoreNull = ignoreNull;
        this.allowAll = allowAll;
    }

    /**
     * 按实体主键更新：若实体实现了 {@link EntityListener} 则先触发 {@code preUpdate()}，再做主键校验与快照。
     */
    static <T> UpdatePlan<T> byPrimaryKey(EntityMetadataContext meta, T entity, boolean ignoreNull, String pk) {
        Objects.requireNonNull(entity, "entity must not be null");
        if (entity instanceof EntityListener listener) {
            listener.preUpdate();
        }
        Object pkValue = meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for update: " + meta.entityType().getName());
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, entity).values();
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        return new UpdatePlan<>(meta, values, WhereCriterion.create().eq(pk, pkValue),
                new LinkedHashSet<>(setColumns), ignoreNull, false);
    }

    /**
     * 显式条件更新：值与 WHERE 由调用方提供，不触发实体写前钩子。
     */
    static UpdatePlan<Void> explicit(EntityMetadataContext meta, Map<String, Object> values,
                                     @Nullable WhereCriterion where, boolean allowAll) {
        return new UpdatePlan<>(meta, values, where, new LinkedHashSet<>(values.keySet()),
                false, allowAll);
    }

    SqlStatement toSqlStatement(ExecutionKernel kernel) {
        MutationContext ctx = new MutationContext(meta, values, where, declared, ignoreNull, allowAll,
                kernel.namingPolicy());
        return UpdateStatementFactory.create(ctx);
    }

    MutationResult execute(ExecutionKernel kernel) {
        long affected = kernel.update(toSqlStatement(kernel));
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
                                               String pk) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<UpdateRowPlan<T>> plans = new ArrayList<>(entities.size());
        for (T row : entities) {
            plans.add(prepareUpdateRow(meta, row, pk));
        }
        return executeBatch(kernel, buildUpdateBatch(kernel, meta, plans, setColumns, pk));
    }

    static <T> MutationResult executeUpdateAllByColumns(ExecutionKernel kernel,
                                                        EntityMetadataContext meta,
                                                        Collection<T> entities,
                                                        Collection<String> columns,
                                                        String pk) {
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
        return executeUpdateAll(kernel, meta, entities, new ArrayList<>(uniqueCols), pk);
    }

    static <T> MutationResult executePatchAll(ExecutionKernel kernel,
                                              EntityMetadataContext meta,
                                              Collection<T> entities,
                                              String pk) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        record UpdateRun<E>(List<String> cols, List<UpdateRowPlan<E>> rows) {
        }
        List<String> candidateCols = new ArrayList<>(meta.updatableColumns());
        candidateCols.remove(pk);
        List<UpdateRun<T>> runs = new ArrayList<>();
        for (T row : entities) {
            UpdateRowPlan<T> plan = prepareUpdateRow(meta, row, pk);
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
                kernel.executionOptions().batchSize());
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

    private static <T> UpdateRowPlan<T> prepareUpdateRow(EntityMetadataContext meta, T row, String pk) {
        Objects.requireNonNull(row, "Entity element in updateAll/patchAll must not be null");
        if (row instanceof EntityListener listener) {
            listener.preUpdate();
        }
        Object pkVal = meta.readFieldValue(row, pk);
        if (pkVal == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for batch update: " + meta.entityType().getName());
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, row).values();
        return new UpdateRowPlan<>(row, values, pkVal);
    }
}
