package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.VersionMetadata;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.exceptions.OptimisticLockException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实体更新计划：单条按主键更新、流式条件更新与批量主键更新共用的唯一 AST 构建与执行入口。
 */
final class UpdatePlan {

    private UpdatePlan() {
    }

    /** 按实体主键更新：触发 {@code preUpdate()} 后基于紧凑数组快照直接生成 {@link AstStatement}。 */
    static <T> MutationResult executeByPrimaryKey(ExecutionKernel kernel,
                                                  EntityMetadataContext meta,
                                                  T entity,
                                                  boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.requirePrimaryKey();
        if (entity instanceof EntityListener listener) {
            listener.preUpdate();
        }
        Object[] snapshot = meta.snapshotValues(entity);
        int pkIdx = meta.columnIndex(pk);
        Object pkValue = pkIdx >= 0 ? snapshot[pkIdx] : meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for update: " + meta.entityType().getName());
        }
        VersionMetadata versionMeta = meta.versionMetadata();
        String versionCol = versionMeta != null ? versionMeta.columnName() : null;
        Object currentVersion = null;
        Object nextVersion = null;
        if (versionMeta != null) {
            currentVersion = versionMeta.requireCurrent(
                    snapshot[versionMeta.columnIndex()], meta.entityType(), pkValue);
            nextVersion = versionMeta.nextValue(currentVersion);
        }
        List<String> candidateCols = meta.updatableColumns();
        List<SqlNode.Assignment> assignments = new ArrayList<>(candidateCols.size() + 1);
        for (String col : candidateCols) {
            if (col.equals(versionCol)) {
                continue;
            }
            Object val = meta.columnValue(snapshot, col);
            if (ignoreNull && val == null) {
                continue;
            }
            assignments.add(SqlNodes.assignment(col,
                    SqlNodes.param(col, meta.convertToDatabase(col, val), ParameterSource.SET)));
        }
        if (assignments.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        SqlNode rowGuard = null;
        if (versionMeta != null) {
            assignments.add(SqlNodes.assignment(versionCol,
                    SqlNodes.param(versionCol, nextVersion, ParameterSource.SET)));
            rowGuard = SqlNodes.eq(
                    SqlNodes.column((SqlNode.Table) null, versionCol),
                    SqlNodes.param(versionCol, currentVersion, ParameterSource.WHERE));
        }
        Object dbPkValue = meta.convertToDatabase(pk, pkValue);
        AstStatement ast = EntityStatements.updateByPkAst(
                meta, assignments, pk, dbPkValue, rowGuard, kernel.namingPolicy());
        long affected = kernel.update(ast);
        if (versionMeta != null) {
            if (affected == 0L) {
                throw OptimisticLockException.of(meta.entityType(), pkValue, currentVersion, "update");
            }
            writeBackVersion(meta, entity, versionCol, nextVersion);
        }
        return MutationResult.ofAffected(affected);
    }

    /** 显式条件更新（供 {@link EntityUpdate} 构建 AST 与直接执行复用）。 */
    static AstStatement buildExplicitAst(EntityMetadataContext meta,
                                         Map<String, Object> values,
                                         @Nullable WhereCriterion where,
                                         boolean allowAll,
                                         NamingPolicy namingPolicy) {
        EntityStatements.validateUpdateColumns(meta, values.keySet());
        List<SqlNode.Assignment> assignments = new ArrayList<>(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String col = Objects.requireNonNullElse(meta.canonicalColumn(entry.getKey()), entry.getKey());
            Object val = entry.getValue();
            SqlNode rhs = (val instanceof SqlNode nodeVal)
                    ? nodeVal
                    : SqlNodes.param(col, meta.convertToDatabaseIfAttribute(col, val), ParameterSource.SET);
            assignments.add(SqlNodes.assignment(col, rhs));
        }
        return EntityStatements.updateAst(meta, assignments, where, allowAll, namingPolicy);
    }

    // ===== 批量更新 =====

    private record UpdateRowPlan<E>(
            E entity,
            Object[] snapshot,
            Object pkValue,
            @Nullable Object currentVersion,
            @Nullable Object nextVersion
    ) {
    }

    static <T> MutationResult executeUpdateAll(ExecutionKernel kernel,
                                               EntityMetadataContext meta,
                                               Collection<T> entities,
                                               List<String> setColumns,
                                               String pk) {
        return executeUpdateAll(kernel, meta, entities, setColumns, pk, null);
    }

    static <T> MutationResult executeUpdateAll(ExecutionKernel kernel,
                                               EntityMetadataContext meta,
                                               Collection<T> entities,
                                               List<String> setColumns,
                                               String pk,
                                               @Nullable List<Runnable> deferredWriteBacks) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        VersionMetadata versionMeta = meta.versionMetadata();
        List<String> effectiveColumns = normalizeBatchSetColumns(setColumns, versionMeta);
        int pkIdx = meta.columnIndex(pk);
        List<UpdateRowPlan<T>> plans = new ArrayList<>(entities.size());
        for (T row : entities) {
            plans.add(prepareUpdateRow(meta, row, pk, pkIdx, versionMeta));
        }
        BatchAstStatement batch = buildUpdateBatch(kernel, meta, plans, effectiveColumns, pk, versionMeta);
        MutationResult result = executeBatch(kernel, batch, meta, plans, versionMeta);
        if (versionMeta != null) {
            Runnable sync = () -> writeBackVersions(meta, plans, versionMeta.columnName());
            if (deferredWriteBacks != null) {
                deferredWriteBacks.add(sync);
            } else {
                sync.run();
            }
        }
        return result;
    }

    static <T> MutationResult executeUpdateAllByColumns(ExecutionKernel kernel,
                                                        EntityMetadataContext meta,
                                                        Collection<T> entities,
                                                        Collection<String> columns,
                                                        String pk) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column must be specified for selective updateAll");
        }
        VersionMetadata versionMeta = meta.versionMetadata();
        String versionCol = versionMeta != null ? versionMeta.columnName() : null;
        Set<String> uniqueCols = new LinkedHashSet<>();
        for (String colOrField : columns) {
            String resolved = meta.requireUpdatableColumn(meta.resolveColumnName(colOrField));
            if (!resolved.equals(pk) && !resolved.equals(versionCol)) {
                uniqueCols.add(resolved);
            }
        }
        if (uniqueCols.isEmpty()) {
            throw new IllegalArgumentException(versionCol != null
                    ? "Update columns must contain at least one non-primary-key, non-version column"
                    : "Update columns must contain at least one non-primary-key column");
        }
        return executeUpdateAll(kernel, meta, entities, new ArrayList<>(uniqueCols), pk);
    }

    static <T> MutationResult executePatchAll(ExecutionKernel kernel,
                                              EntityMetadataContext meta,
                                              Collection<T> entities,
                                              String pk) {
        return executePatchAll(kernel, meta, entities, pk, null);
    }

    static <T> MutationResult executePatchAll(ExecutionKernel kernel,
                                              EntityMetadataContext meta,
                                              Collection<T> entities,
                                              String pk,
                                              @Nullable List<Runnable> deferredWriteBacks) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        record UpdateRun<E>(List<String> cols, List<UpdateRowPlan<E>> rows) {
        }
        VersionMetadata versionMeta = meta.versionMetadata();
        String versionCol = versionMeta != null ? versionMeta.columnName() : null;
        int pkIdx = meta.columnIndex(pk);
        List<String> candidateCols = meta.updatableColumns();
        List<UpdateRun<T>> runs = new ArrayList<>();
        List<UpdateRowPlan<T>> allPlans = versionMeta != null ? new ArrayList<>(entities.size()) : null;
        for (T row : entities) {
            UpdateRowPlan<T> plan = prepareUpdateRow(meta, row, pk, pkIdx, versionMeta);
            if (allPlans != null) {
                allPlans.add(plan);
            }
            List<String> nonNullCols = new ArrayList<>(candidateCols.size());
            for (String col : candidateCols) {
                if (col.equals(versionCol)) {
                    continue;
                }
                if (meta.columnValue(plan.snapshot(), col) != null) {
                    nonNullCols.add(col);
                }
            }
            if (nonNullCols.isEmpty()) {
                throw new IllegalStateException("UPDATE statement has no columns to set");
            }
            if (versionCol != null) {
                nonNullCols.add(versionCol);
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
            BatchAstStatement batch = buildUpdateBatch(kernel, meta, run.rows(), run.cols(), pk, versionMeta);
            result = result.merge(executeBatch(kernel, batch, meta, run.rows(), versionMeta));
        }
        // 两阶段内存回写：仅在全部批分组（含外层 saveAll 跨分片）无任何乐观锁冲突后统一推进实体内存版本号
        if (versionMeta != null && allPlans != null) {
            Runnable sync = () -> writeBackVersions(meta, allPlans, versionCol);
            if (deferredWriteBacks != null) {
                deferredWriteBacks.add(sync);
            } else {
                sync.run();
            }
        }
        return result;
    }

    private static List<String> normalizeBatchSetColumns(List<String> setColumns,
                                                         @Nullable VersionMetadata versionMeta) {
        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        if (versionMeta == null) {
            return setColumns;
        }
        String versionCol = versionMeta.columnName();
        List<String> cols = new ArrayList<>(setColumns.size() + 1);
        for (String col : setColumns) {
            if (!col.equals(versionCol)) {
                cols.add(col);
            }
        }
        if (cols.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        cols.add(versionCol);
        return cols;
    }

    private static <T> @Nullable BatchAstStatement buildUpdateBatch(ExecutionKernel kernel,
                                                                    EntityMetadataContext meta,
                                                                    List<UpdateRowPlan<T>> plans,
                                                                    List<String> setColumns,
                                                                    String pk,
                                                                    @Nullable VersionMetadata versionMeta) {
        if (plans == null || plans.isEmpty()) {
            return null;
        }
        if (setColumns == null || setColumns.isEmpty()) {
            throw new IllegalStateException("UPDATE statement has no columns to set");
        }
        boolean versioned = versionMeta != null;
        int businessSetSize = versioned ? setColumns.size() - 1 : setColumns.size();
        int[] colIndices = new int[businessSetSize];
        for (int i = 0; i < businessSetSize; i++) {
            colIndices[i] = meta.columnIndex(setColumns.get(i));
        }
        int rowLen = setColumns.size() + (versioned ? 2 : 1);
        List<Object[]> rows = new ArrayList<>(plans.size());
        for (UpdateRowPlan<T> plan : plans) {
            Object[] values = new Object[rowLen];
            Object[] snap = plan.snapshot();
            for (int i = 0; i < businessSetSize; i++) {
                int idx = colIndices[i];
                values[i] = idx >= 0 ? meta.convertToDatabaseAt(idx, snap[idx]) : null;
            }
            if (versioned) {
                values[businessSetSize] = plan.nextVersion();
                values[businessSetSize + 1] = meta.convertToDatabase(pk, plan.pkValue());
                values[businessSetSize + 2] = plan.currentVersion();
            } else {
                values[businessSetSize] = meta.convertToDatabase(pk, plan.pkValue());
            }
            rows.add(values);
        }
        return EntityStatements.updateBatchByPkAst(
                meta,
                setColumns,
                pk,
                versioned ? versionMeta.columnName() : null,
                rows,
                kernel.namingPolicy(),
                kernel.executionOptions().batchSize());
    }

    private static <T> MutationResult executeBatch(ExecutionKernel kernel,
                                                   @Nullable BatchAstStatement batch,
                                                   EntityMetadataContext meta,
                                                   List<UpdateRowPlan<T>> plans,
                                                   @Nullable VersionMetadata versionMeta) {
        if (batch == null || batch.rowCount() == 0) {
            return MutationResult.EMPTY;
        }
        BatchSlice slice = versionMeta != null
                ? kernel.batchExecuteExact(batch)
                : kernel.batchExecute(batch);
        if (versionMeta != null) {
            int[] counts = slice.batchCounts();
            for (int i = 0; i < plans.size(); i++) {
                int affected = i < counts.length ? counts[i] : 0;
                if (affected <= 0) {
                    UpdateRowPlan<T> failed = plans.get(i);
                    throw OptimisticLockException.of(
                            meta.entityType(), failed.pkValue(), failed.currentVersion(), "batch update");
                }
            }
        }
        return slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    private static <T> UpdateRowPlan<T> prepareUpdateRow(EntityMetadataContext meta,
                                                         T row,
                                                         String pk,
                                                         int pkIdx,
                                                         @Nullable VersionMetadata versionMeta) {
        Objects.requireNonNull(row, "Entity element in updateAll/patchAll must not be null");
        if (row instanceof EntityListener listener) {
            listener.preUpdate();
        }
        Object[] snapshot = meta.snapshotValues(row);
        Object pkVal = pkIdx >= 0 ? snapshot[pkIdx] : meta.readFieldValue(row, pk);
        if (pkVal == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for batch update: " + meta.entityType().getName());
        }
        if (versionMeta != null) {
            Object current = versionMeta.requireCurrent(
                    snapshot[versionMeta.columnIndex()], meta.entityType(), pkVal);
            Object next = versionMeta.nextValue(current);
            return new UpdateRowPlan<>(row, snapshot, pkVal, current, next);
        }
        return new UpdateRowPlan<>(row, snapshot, pkVal, null, null);
    }

    private static <T> void writeBackVersions(EntityMetadataContext meta,
                                              List<UpdateRowPlan<T>> plans,
                                              String versionCol) {
        if (meta.entityType().isRecord()) {
            return;
        }
        for (UpdateRowPlan<T> plan : plans) {
            writeBackVersion(meta, plan.entity(), versionCol, plan.nextVersion());
        }
    }

    private static void writeBackVersion(EntityMetadataContext meta,
                                         Object entity,
                                         String versionCol,
                                         @Nullable Object nextVersion) {
        if (nextVersion == null || meta.entityType().isRecord()) {
            return;
        }
        meta.writeFieldValue(entity, versionCol, nextVersion);
    }
}
