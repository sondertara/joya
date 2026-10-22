package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.kernel.ExecutionOptions;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批量写/更新规划器：负责集合分片、按列签名归组、提取批参数矩阵并交由 {@link InsertStatementFactory} / {@link UpdateStatementFactory} 构造批处理指令。
 */
final class BatchPlanner {

    private BatchPlanner() {
    }

    static <T> MutationResult executeInsertAll(
            JoyaTemplate template,
            EntityMetadataContext meta,
            Collection<T> entities,
            boolean ignoreNull) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        String pk = meta.primaryKey();
        boolean pkGenerated = pk != null && meta.generatedColumns().contains(pk);

        if (!ignoreNull) {
            List<T> rowsWithoutKey = new ArrayList<>();
            List<T> rowsWithKey = new ArrayList<>();
            for (T row : entities) {
                Objects.requireNonNull(row, "Entity element in insertAll must not be null");
                if (row instanceof EntityListener listener) {
                    listener.prePersist(row);
                }
                Object pkVal = pk == null ? null : meta.readFieldValue(row, pk);
                if (pkVal == null) {
                    rowsWithoutKey.add(row);
                } else {
                    rowsWithKey.add(row);
                }
            }
            MutationResult r1 = GeneratedKeyWriter.executeInsertBatchWithKeys(
                    template, meta, rowsWithoutKey, meta.insertableColumns(), pkGenerated);
            MutationResult r2 = GeneratedKeyWriter.executeInsertBatchWithKeys(
                    template, meta, rowsWithKey, meta.columnNames(), false);
            return r1.merge(r2);
        }

        Map<List<String>, List<T>> groups = new LinkedHashMap<>();
        for (T row : entities) {
            Objects.requireNonNull(row, "Entity element in insertAllIgnoreNull must not be null");
            if (row instanceof EntityListener listener) {
                listener.prePersist(row);
            }
            Object pkVal = pk == null ? null : meta.readFieldValue(row, pk);
            List<String> candidateCols = pkVal != null ? meta.columnNames() : meta.insertableColumns();
            List<String> nonNullCols = new ArrayList<>(candidateCols.size());
            for (String col : candidateCols) {
                if (meta.readFieldValue(row, col) != null) {
                    nonNullCols.add(col);
                }
            }
            groups.computeIfAbsent(List.copyOf(nonNullCols), k -> new ArrayList<>()).add(row);
        }

        MutationResult total = MutationResult.EMPTY;
        for (Map.Entry<List<String>, List<T>> entry : groups.entrySet()) {
            List<String> cols = entry.getKey();
            boolean wantKeys = pkGenerated && !cols.contains(pk);
            total = total.merge(GeneratedKeyWriter.executeInsertBatchWithKeys(
                    template, meta, entry.getValue(), cols, wantKeys));
        }
        return total;
    }

    static <T> MutationResult executeUpdateAll(
            JoyaTemplate template,
            EntityMetadataContext meta,
            Collection<T> entities,
            List<String> setColumns,
            String pk) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<T> list = toList(entities);
        for (T row : list) {
            prepareUpdateRow(meta, row, pk);
        }
        return executeBatch(template, buildUpdateBatch(template, meta, list, setColumns, pk));
    }

    static <T> MutationResult executeUpdateAllByColumns(
            JoyaTemplate template,
            EntityMetadataContext meta,
            Collection<T> entities,
            Collection<String> columns,
            String pk) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one column must be specified for selective updateAll");
        }
        java.util.Set<String> uniqueCols = new java.util.LinkedHashSet<>();
        for (String colOrField : columns) {
            String resolved = EntityOps.resolveColumnName(meta, colOrField);
            if (!resolved.equals(pk)) {
                uniqueCols.add(resolved);
            }
        }
        if (uniqueCols.isEmpty()) {
            throw new IllegalArgumentException("Update columns must contain at least one non-primary-key column");
        }
        return executeUpdateAll(template, meta, entities, new ArrayList<>(uniqueCols), pk);
    }

    static <T> MutationResult executePatchAll(
            JoyaTemplate template,
            EntityMetadataContext meta,
            Collection<T> entities,
            String pk) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<String> candidateCols = new ArrayList<>(meta.updatableColumns());
        candidateCols.remove(pk);
        Map<List<String>, List<T>> groups = new LinkedHashMap<>();
        for (T row : entities) {
            prepareUpdateRow(meta, row, pk);
            List<String> nonNullCols = new ArrayList<>(candidateCols.size());
            for (String col : candidateCols) {
                if (meta.readFieldValue(row, col) != null) {
                    nonNullCols.add(col);
                }
            }
            if (!nonNullCols.isEmpty()) {
                groups.computeIfAbsent(List.copyOf(nonNullCols), k -> new ArrayList<>()).add(row);
            }
        }
        MutationResult result = MutationResult.EMPTY;
        for (Map.Entry<List<String>, List<T>> entry : groups.entrySet()) {
            result = result.merge(executeBatch(
                    template, buildUpdateBatch(template, meta, entry.getValue(), entry.getKey(), pk)));
        }
        return result;
    }

    static <T> BatchSqlStatement buildInsertBatch(
            JoyaTemplate template,
            EntityMetadataContext meta,
            List<T> rows,
            List<String> columns,
            boolean returnGeneratedKeys) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return InsertStatementFactory.createBatch(
                meta,
                columns,
                rowsOf(meta, rows, columns),
                template.namingPolicy(),
                effectiveBatchSize(template),
                returnGeneratedKeys);
    }

    static <T> BatchSqlStatement buildUpdateBatch(
            JoyaTemplate template,
            EntityMetadataContext meta,
            List<T> rows,
            List<String> setColumns,
            String pk) {
        if (rows == null || rows.isEmpty() || setColumns.isEmpty()) {
            return null;
        }
        List<String> allColumns = new ArrayList<>(setColumns.size() + 1);
        allColumns.addAll(setColumns);
        allColumns.add(pk);
        return UpdateStatementFactory.createBatchByPrimaryKey(
                meta,
                setColumns,
                pk,
                rowsOf(meta, rows, allColumns),
                template.namingPolicy(),
                effectiveBatchSize(template));
    }

    static MutationResult executeBatch(JoyaTemplate template, BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return MutationResult.EMPTY;
        }
        BatchSlice slice = template.batchExecute(batch);
        return slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    static <T> List<Object[]> rowsOf(EntityMetadataContext meta, List<T> rows, List<String> columns) {
        List<Object[]> batchRows = new ArrayList<>(rows.size());
        int colSize = columns.size();
        for (T row : rows) {
            Object[] values = new Object[colSize];
            for (int i = 0; i < colSize; i++) {
                values[i] = meta.readFieldValue(row, columns.get(i));
            }
            batchRows.add(values);
        }
        return batchRows;
    }

    static void prepareUpdateRow(EntityMetadataContext meta, Object row, String pk) {
        Objects.requireNonNull(row, "Entity element in updateAll/patchAll must not be null");
        if (row instanceof EntityListener listener) {
            listener.preUpdate(row);
        }
        Object pkVal = meta.readFieldValue(row, pk);
        if (pkVal == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for batch update: " + meta.entityType().getName());
        }
    }

    static int effectiveBatchSize(JoyaTemplate template) {
        int configured = template.executionOptions().batchSize();
        return configured > 0 ? configured : ExecutionOptions.DEFAULT_BATCH_SIZE;
    }

    static <E> List<E> toList(Collection<E> items) {
        return items instanceof List<E> l ? l : new ArrayList<>(items);
    }
}
