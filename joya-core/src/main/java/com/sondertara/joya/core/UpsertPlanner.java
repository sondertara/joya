package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.TypeUtils;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Upsert 规划器：负责单条与批量 {@code save} / {@code saveAll} 的主键存在性分片探测与插入/更新路由。
 */
final class UpsertPlanner<T> {

    private static final Set<Operation> UPSERT_GUARD_OPERATIONS =
            Set.of(Operation.SELECT, Operation.INSERT, Operation.UPDATE);

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final EntityFinder<T> finder;

    UpsertPlanner(ExecutionKernel kernel, EntityMetadataContext meta, EntityFinder<T> finder) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.finder = Objects.requireNonNull(finder, "finder must not be null");
    }

    MutationResult save(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : meta.readFieldValue(entity, pk);
        boolean hasListener = entity instanceof EntityListener;
        if (pkVal instanceof Serializable) {
            Dialect dialect = kernel.dialect();
            if (!requiresGuardedFallback(dialect) && canUseNativeUpsert(dialect.upsertStyle())) {
                MutationResult nativeResult = nativeUpsert(entity, ignoreNull, pkVal, hasListener, dialect);
                if (nativeResult != null) {
                    return nativeResult;
                }
            }
        }
        boolean exists = (pkVal instanceof Serializable s) && finder.existsById(s);
        if (!exists) {
            return new InsertPlan<>(meta, List.of(entity), ignoreNull, "entity must not be null", Map.of())
                    .execute(kernel);
        }
        MutationResult updated = UpdatePlan.executeByPrimaryKey(kernel, meta, entity, ignoreNull);
        return MutationResult.ofInsert(updated.affectedRows(), pkVal);
    }

    private @Nullable MutationResult nativeUpsert(T entity, boolean ignoreNull, Object pkVal,
                                                  boolean hasListener, Dialect dialect) {
        String pk = meta.primaryKey();
        if (pk == null) {
            return null;
        }
        if (hasListener && entity instanceof EntityListener listener) {
            fireUpsertListener(listener, finder.existsById((Serializable) pkVal));
        }
        Object[] snapshot = meta.snapshotValues(entity);
        List<String> columns = selectUpsertColumns(snapshot, pk, ignoreNull);
        if (columns.isEmpty()) {
            return null;
        }
        List<Object> values = new ArrayList<>(columns.size());
        for (String col : columns) {
            values.add(meta.convertToDatabase(col, meta.columnValue(snapshot, col)));
        }
        AstStatement upsert = EntityStatements.upsertAst(
                meta, columns, values, pk, dialect.upsertStyle(), dialect.dbType(), kernel.namingPolicy());
        int affected = kernel.update(upsert);
        return MutationResult.ofInsert(affected, pkVal);
    }

    private @Nullable MutationResult nativeBatchGroups(List<T> rows,
                                                       String pk,
                                                       boolean ignoreNull,
                                                       int batchSize,
                                                       boolean hasListeners,
                                                       Set<Object> existing,
                                                       Dialect dialect) {
        if (hasListeners) {
            for (T row : rows) {
                if (row instanceof EntityListener listener) {
                    boolean exists = existing.contains(normalizePrimaryKey(meta.readFieldValue(row, pk)));
                    fireUpsertListener(listener, exists);
                }
            }
        }
        Map<List<String>, List<Object[]>> groups = new LinkedHashMap<>();
        for (T row : rows) {
            Object[] snapshot = meta.snapshotValues(row);
            List<String> columns = selectUpsertColumns(snapshot, pk, ignoreNull);
            if (columns.isEmpty()) {
                return null;
            }
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i);
                args[i] = meta.convertToDatabase(col, meta.columnValue(snapshot, col));
            }
            groups.computeIfAbsent(List.copyOf(columns), key -> new ArrayList<>()).add(args);
        }
        MutationResult total = MutationResult.EMPTY;
        for (Map.Entry<List<String>, List<Object[]>> entry : groups.entrySet()) {
            BatchAstStatement upsertBatch = EntityStatements.upsertBatchAst(
                    meta, entry.getKey(), entry.getValue(), kernel.namingPolicy(),
                    batchSize, pk, dialect.upsertStyle(), dialect.dbType());
            BatchSlice slice = kernel.batchExecute(upsertBatch);
            total = total.merge(MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys()));
        }
        return total;
    }

    private static void fireUpsertListener(EntityListener listener, boolean exists) {
        if (exists) {
            listener.preUpdate();
        } else {
            listener.prePersist();
        }
    }

    private List<String> selectUpsertColumns(Object[] snapshot, String pk, boolean ignoreNull) {
        List<String> candidateColumns = meta.insertableColumnsWithPrimaryKey();
        if (!ignoreNull) {
            return candidateColumns;
        }
        List<String> columns = new ArrayList<>(candidateColumns.size());
        for (String column : candidateColumns) {
            if (meta.columnValue(snapshot, column) != null || column.equals(pk)) {
                columns.add(column);
            }
        }
        return columns;
    }

    MutationResult saveAll(Collection<T> entities, boolean ignoreNull) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<T> rows = entities instanceof List<T> l ? l : new ArrayList<>(entities);
        String pk = meta.requirePrimaryKey();
        List<Object> primaryKeys = new ArrayList<>(rows.size());
        Set<Object> seenPrimaryKeys = new HashSet<>();
        boolean hasListeners = false;
        boolean allKeysAssigned = true;
        for (T row : rows) {
            Objects.requireNonNull(row, "Entity element in saveAll must not be null");
            if (row instanceof EntityListener) {
                hasListeners = true;
            }
            Object pkVal = meta.readFieldValue(row, pk);
            Object normalizedPk = normalizePrimaryKey(pkVal);
            if (normalizedPk == null) {
                allKeysAssigned = false;
            } else if (!seenPrimaryKeys.add(normalizedPk)) {
                throw new IllegalArgumentException(
                        "Duplicate primary key [" + pkVal + "] in saveAll batch for entity "
                                + meta.entityType().getName());
            }
            primaryKeys.add(normalizedPk);
        }

        int batchPartitionSize = kernel.executionOptions().batchSize();

        if (allKeysAssigned) {
            Dialect dialect = kernel.dialect();
            if (!requiresGuardedFallback(dialect) && canUseNativeUpsert(dialect.upsertStyle())) {
                Set<Object> existing = hasListeners
                        ? findExistingIds(kernel, meta, pk, primaryKeys)
                        : Set.of();
                MutationResult nativeBatch = nativeBatchGroups(rows, pk, ignoreNull,
                        batchPartitionSize, hasListeners, existing, dialect);
                if (nativeBatch != null) {
                    return nativeBatch;
                }
            }
        }

        // 跨分片两阶段内存回写：全部 chunk 成功后再统一回写生成主键与 @Version 版本号
        List<Runnable> deferredWriteBacks = new ArrayList<>();
        MutationResult total = MutationResult.EMPTY;
        for (int start = 0; start < rows.size(); start += batchPartitionSize) {
            int end = Math.min(start + batchPartitionSize, rows.size());
            List<T> toInsert = new ArrayList<>();
            List<T> toUpdate = new ArrayList<>();
            Map<Object, T> keyed = new LinkedHashMap<>();

            for (int i = start; i < end; i++) {
                T row = rows.get(i);
                Object pkVal = primaryKeys.get(i);
                if (pkVal == null) {
                    toInsert.add(row);
                } else {
                    keyed.put(pkVal, row);
                }
            }
            if (!keyed.isEmpty()) {
                Set<Object> existing = findExistingIds(kernel, meta, pk, keyed.keySet());
                keyed.forEach((pkVal, row) -> {
                    if (existing.contains(pkVal)) {
                        toUpdate.add(row);
                    } else {
                        toInsert.add(row);
                    }
                });
            }
            if (!toUpdate.isEmpty()) {
                MutationResult updated = ignoreNull
                        ? UpdatePlan.executePatchAll(kernel, meta, toUpdate, pk, deferredWriteBacks)
                        : UpdatePlan.executeUpdateAll(kernel, meta, toUpdate, meta.updatableColumns(), pk, deferredWriteBacks);
                total = total.merge(updated);
            }
            if (!toInsert.isEmpty()) {
                String msg = ignoreNull
                        ? "Entity element in insertAllIgnoreNull must not be null"
                        : "Entity element in insertAll must not be null";
                total = total.merge(new InsertPlan<>(meta, toInsert, ignoreNull, msg, Map.of())
                        .execute(kernel, deferredWriteBacks));
            }
        }
        for (Runnable writeBack : deferredWriteBacks) {
            writeBack.run();
        }
        return total;
    }

    private static Set<Object> findExistingIds(
            ExecutionKernel kernel,
            EntityMetadataContext meta,
            String pk,
            Collection<?> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Class<?> pkType = meta.primaryKeyType();
        ColumnConverter pkConverter = meta.converterFor(pk);
        Class<?> dbPkType = pkConverter != null ? pkConverter.databaseColumnType() : pkType;
        Class<?> dbPkBoxed = dbPkType != null ? TypeUtils.getWrapClass(dbPkType) : Object.class;
        SqlDataUtils.ColumnReader pkReader = (dbPkType != null && dbPkType != Object.class)
                ? SqlDataUtils.readerFor(dbPkType)
                : SqlDataUtils::getResultSetValue;
        List<Object> keyList = keys instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(keys);
        Set<Object> existing = new HashSet<>(keyList.size());

        for (List<Object> chunk : Partitions.of(keyList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            WhereCriterion where = WhereCriterion.create().inTrusted(pk, chunk);
            AstStatement stmt = EntityStatements.selectColumnsAst(meta, where, Set.of(pk), kernel.namingPolicy());
            List<Object> rows = kernel.queryList(stmt, rs -> {
                Object raw = pkReader.read(rs, 1, null);
                if (raw != null && dbPkType != null && dbPkType != Object.class && !dbPkBoxed.isInstance(raw)) {
                    raw = ConvertUtils.convert(raw, dbPkType);
                }
                if (pkConverter != null) {
                    raw = pkConverter.toEntity(raw);
                }
                return normalizePrimaryKey(raw);
            });
            existing.addAll(rows);
        }
        return existing;
    }

    private boolean requiresGuardedFallback(Dialect dialect) {
        if (!kernel.hasRewriters()) {
            return false;
        }
        Set<TableRef> tables = Set.of(TableRef.ofQualified(meta.qualifiedTableName(kernel.namingPolicy()), ""));
        return kernel.hasApplicableRewriters(
                UPSERT_GUARD_OPERATIONS, meta.entityType(), tables, dialect.dbType());
    }

    private boolean canUseNativeUpsert(UpsertStyle style) {
        if (meta.hasVersion()) {
            return false;
        }
        if (style == UpsertStyle.NONE || meta.insertableColumnsWithPrimaryKey().isEmpty()) {
            return false;
        }
        if (style == UpsertStyle.MERGE) {
            String pk = meta.primaryKey();
            for (String col : meta.insertableColumns()) {
                if (!col.equalsIgnoreCase(pk) && meta.nonUpdatableColumns().contains(col)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static @Nullable Object normalizePrimaryKey(@Nullable Object key) {
        if (key instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        return key;
    }
}
