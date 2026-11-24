package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.VersionMetadata;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实体插入计划：单条插入与批量插入共用的唯一规划/校验/执行入口。
 *
 * <p>基于 {@link EntityMetadataContext#snapshotValues(Object)} 紧凑数组一次提取列值（零 {@code LinkedHashMap} 分配），
 * 并直接向 {@link ExecutionKernel} 提交 {@link AstStatement} / {@link BatchAstStatement} 单趟编译。
 */
final class InsertPlan<T> {

    private static final Logger log = LoggerFactory.getLogger(InsertPlan.class);

    private final EntityMetadataContext meta;
    private final List<T> entities;
    private final boolean ignoreNull;
    private final Map<String, Object> overrides;

    InsertPlan(EntityMetadataContext meta, Collection<T> entities, boolean ignoreNull, String nullMessage,
               Map<String, Object> overrides) {
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        this.entities = new ArrayList<>(entities.size());
        for (T entity : entities) {
            this.entities.add(Objects.requireNonNull(entity, nullMessage));
        }
        this.ignoreNull = ignoreNull;
        this.overrides = (overrides == null || overrides.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    private record RowPlan<E>(E entity, Object[] snapshot, List<String> columns,
                              @Nullable Object initializedVersion) {
    }

    private Object[] snapshotOf(T entity) {
        Object[] snapshot = meta.snapshotValues(entity);
        if (!overrides.isEmpty()) {
            for (Map.Entry<String, Object> entry : overrides.entrySet()) {
                int idx = meta.columnIndex(entry.getKey());
                if (idx >= 0) {
                    snapshot[idx] = entry.getValue();
                }
            }
        }
        return snapshot;
    }

    MutationResult execute(ExecutionKernel kernel) {
        return execute(kernel, null);
    }

    MutationResult execute(ExecutionKernel kernel, @Nullable List<Runnable> externalDeferredWriteBacks) {
        if (entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        String pk = meta.primaryKey();
        int pkIndex = pk != null ? meta.columnIndex(pk) : -1;
        boolean pkGenerated = pk != null && meta.generatedColumns().contains(pk);

        VersionMetadata versionMeta = meta.versionMetadata();
        record InsertRun<E>(List<String> cols, List<RowPlan<E>> rows) {
        }
        List<InsertRun<T>> runs = new ArrayList<>();
        for (T entity : entities) {
            if (entity instanceof EntityListener listener) {
                listener.prePersist();
            }
            Object[] snapshot = snapshotOf(entity);
            Object initializedVersion = null;
            if (versionMeta != null) {
                int vIdx = versionMeta.columnIndex();
                if (snapshot[vIdx] == null) {
                    initializedVersion = versionMeta.initialValue();
                    snapshot[vIdx] = initializedVersion;
                }
            }
            List<String> cols = columnsFor(snapshot, pkIndex);
            RowPlan<T> row = new RowPlan<>(entity, snapshot, cols, initializedVersion);
            if (!runs.isEmpty() && runs.get(runs.size() - 1).cols().equals(cols)) {
                runs.get(runs.size() - 1).rows().add(row);
            } else {
                List<RowPlan<T>> rows = new ArrayList<>();
                rows.add(row);
                runs.add(new InsertRun<>(cols, rows));
            }
        }

        // 跨多列分组或外层跨分片（saveAll）时统一延后内存回写，确保任意后段失败回滚不污染前段实体内存
        List<Runnable> deferred = externalDeferredWriteBacks != null
                ? externalDeferredWriteBacks
                : (runs.size() > 1 ? new ArrayList<>() : null);

        MutationResult total = MutationResult.EMPTY;
        for (InsertRun<T> run : runs) {
            MutationResult result;
            if (runs.size() == 1 && run.rows().size() == 1) {
                result = insertSingle(kernel, run.rows().get(0), pkIndex, deferred);
            } else {
                boolean wantKeys = pkGenerated && !run.cols().contains(pk);
                result = insertBatch(kernel, run.rows(), run.cols(), wantKeys, deferred);
            }
            total = total.merge(result);
        }
        if (externalDeferredWriteBacks == null && deferred != null) {
            for (Runnable writeBack : deferred) {
                writeBack.run();
            }
        }
        return total;
    }

    static List<String> candidateColumns(EntityMetadataContext meta, @Nullable Object pkValue) {
        return pkValue != null ? meta.insertableColumnsWithPrimaryKey() : meta.insertableColumns();
    }

    private List<String> columnsFor(Object[] snapshot, int pkIndex) {
        Object pkValue = pkIndex >= 0 ? snapshot[pkIndex] : null;
        meta.requireAssignedPrimaryKey(pkValue);
        List<String> candidate = candidateColumns(meta, pkValue);
        List<String> cols;
        if (ignoreNull) {
            cols = new ArrayList<>(candidate.size());
            for (String col : candidate) {
                if (meta.columnValue(snapshot, col) != null) {
                    cols.add(col);
                }
            }
            cols = List.copyOf(cols);
        } else {
            cols = candidate;
        }
        if (cols.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }
        return cols;
    }

    private MutationResult insertSingle(ExecutionKernel kernel,
                                        RowPlan<T> row,
                                        int pkIndex,
                                        @Nullable List<Runnable> deferredWriteBacks) {
        List<String> cols = row.columns();
        List<Object> vals = new ArrayList<>(cols.size());
        boolean hasOverrides = !overrides.isEmpty();
        for (String col : cols) {
            Object raw = meta.columnValue(row.snapshot(), col);
            vals.add(hasOverrides ? meta.convertToDatabaseIfAttribute(col, raw) : meta.convertToDatabase(col, raw));
        }
        AstStatement astStmt = EntityStatements.insertAst(meta, cols, vals, kernel.namingPolicy());
        Object existingPkValue = pkIndex >= 0 ? row.snapshot()[pkIndex] : null;
        MutationResult result = executeSingleAst(kernel, meta, astStmt, row.entity(), existingPkValue, deferredWriteBacks);
        if (row.initializedVersion() != null) {
            Runnable verSync = () -> writeBackVersion(meta, row.entity(), row.initializedVersion());
            if (deferredWriteBacks != null) {
                deferredWriteBacks.add(verSync);
            } else {
                verSync.run();
            }
        }
        return result;
    }

    private MutationResult insertBatch(ExecutionKernel kernel,
                                       List<RowPlan<T>> rows,
                                       List<String> columns,
                                       boolean wantKeys,
                                       @Nullable List<Runnable> deferredWriteBacks) {
        int colCount = columns.size();
        int[] colIndices = new int[colCount];
        for (int i = 0; i < colCount; i++) {
            colIndices[i] = meta.columnIndex(columns.get(i));
        }
        boolean hasOverrides = !overrides.isEmpty();
        List<Object[]> batchRows = new ArrayList<>(rows.size());
        for (RowPlan<T> row : rows) {
            Object[] values = new Object[colCount];
            Object[] snap = row.snapshot();
            for (int i = 0; i < colCount; i++) {
                int idx = colIndices[i];
                if (idx >= 0) {
                    values[i] = hasOverrides
                            ? meta.convertToDatabaseIfAttribute(columns.get(i), snap[idx])
                            : meta.convertToDatabaseAt(idx, snap[idx]);
                }
            }
            batchRows.add(values);
        }
        int batchSize = kernel.executionOptions().batchSize();
        BatchAstStatement batch = EntityStatements.insertBatchAst(
                meta, columns, batchRows, kernel.namingPolicy(), batchSize, wantKeys);
        BatchSlice slice = kernel.batchExecute(batch);
        MutationResult result = slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
        Runnable syncAction = () -> {
            if (wantKeys) {
                if (slice.generatedKeys().size() == rows.size()) {
                    for (int i = 0; i < rows.size(); i++) {
                        writeBackPrimaryKey(meta, rows.get(i).entity(), slice.generatedKeys().get(i));
                    }
                } else {
                    log.warn("Generated key count mismatch for batch insert on {}: expected {} but got {}; "
                                    + "primary keys will not be written back",
                            meta.entityType().getName(), rows.size(), slice.generatedKeys().size());
                }
            }
            for (RowPlan<T> row : rows) {
                if (row.initializedVersion() != null) {
                    writeBackVersion(meta, row.entity(), row.initializedVersion());
                }
            }
        };
        if (deferredWriteBacks != null) {
            deferredWriteBacks.add(syncAction);
        } else {
            syncAction.run();
        }
        return result;
    }

    private static void writeBackVersion(EntityMetadataContext meta, Object entity, Object version) {
        VersionMetadata versionMeta = meta.versionMetadata();
        if (versionMeta == null || entity == null || meta.entityType().isRecord()) {
            return;
        }
        try {
            meta.writeFieldValue(entity, versionMeta.columnName(), version);
        } catch (Exception ignored) {
            log.debug("Failed to write back initial @Version to entity {}: {}",
                    meta.entityType().getName(), ignored.getMessage());
        }
    }

    private static void writeBackPrimaryKey(EntityMetadataContext meta, Object entity, Object generatedKey) {
        String pkCol = meta.primaryKey();
        if (pkCol == null || generatedKey == null || entity == null) {
            return;
        }
        try {
            ColumnConverter pkConverter = meta.converterFor(pkCol);
            Object converted;
            if (pkConverter != null) {
                Class<?> dbType = pkConverter.databaseColumnType();
                Object dbKey = (dbType != Object.class && !TypeUtils.getWrapClass(dbType).isInstance(generatedKey))
                        ? ConvertUtils.convert(generatedKey, dbType)
                        : generatedKey;
                converted = pkConverter.toEntity(dbKey);
            } else {
                Class<?> pkType = meta.primaryKeyType();
                converted = (pkType != null && !pkType.isInstance(generatedKey))
                        ? ConvertUtils.convert(generatedKey, pkType)
                        : generatedKey;
            }
            meta.writeFieldValue(entity, pkCol, converted);
        } catch (Exception ignored) {
            log.debug("Failed to write back generated key to entity {}: {}",
                    meta.entityType().getName(), ignored.getMessage());
        }
    }

    static <T> MutationResult executeSingleAst(
            ExecutionKernel kernel,
            EntityMetadataContext meta,
            AstStatement statement,
            @Nullable T sourceEntity,
            @Nullable Object existingPkValue) {
        return executeSingleAst(kernel, meta, statement, sourceEntity, existingPkValue, null);
    }

    static <T> MutationResult executeSingleAst(
            ExecutionKernel kernel,
            EntityMetadataContext meta,
            AstStatement statement,
            @Nullable T sourceEntity,
            @Nullable Object existingPkValue,
            @Nullable List<Runnable> deferredWriteBacks) {
        var slice = kernel.insertExecute(statement);
        Object generatedKey = slice.generatedKey();
        if (sourceEntity != null && existingPkValue == null && generatedKey != null) {
            Runnable pkSync = () -> writeBackPrimaryKey(meta, sourceEntity, generatedKey);
            if (deferredWriteBacks != null) {
                deferredWriteBacks.add(pkSync);
            } else {
                pkSync.run();
            }
        }
        return MutationResult.ofInsert(slice.affectedRows(),
                generatedKey != null ? generatedKey : existingPkValue);
    }
}
