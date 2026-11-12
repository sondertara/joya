package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;
import com.sondertara.joya.core.statement.factory.UpsertStatementFactory;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ConvertUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.SQLException;
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
 *
 * <p><b>实现策略</b>：单条 {@code save} 与满足条件的 {@code saveAll} / {@code saveAllIgnoreNull}
 * （原生方言、无匹配改写器、主键非空）走原生 upsert；只要 {@code SELECT}/{@code INSERT}/{@code UPDATE}
 * 任一操作存在可能生效的 {@code SqlRewriter}（如多租户行级过滤、审计列注入），单条与批量一致回退到
 * 受 {@code WHERE} 保护的 select-then-write（可见则更新、不可见则插入），避免无条件冲突更新绕过行级过滤。
 * 回退路径在并发插入同一主键时存在竞态，强一致性场景请使用显式事务 + 唯一约束；
 * 批量回退在自动提交模式下跨连接执行，需要原子性时请包在 {@code joya.tx(...)} 内。
 */
final class UpsertPlanner<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final EntityLifecycle lifecycle;
    private final EntityWriter<T> writer;
    private final EntityFinder<T> finder;

    UpsertPlanner(ExecutionKernel kernel,
                  EntityMetadataContext meta,
                  EntityLifecycle lifecycle,
                  EntityWriter<T> writer,
                  EntityFinder<T> finder) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.finder = Objects.requireNonNull(finder, "finder must not be null");
    }

    MutationResult save(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : meta.readFieldValue(entity, pk);
        boolean hasListeners = lifecycle.hasListenersFor(entity);
        if (pkVal instanceof Serializable) {
            Dialect dialect = kernel.dialect();
            if (!requiresGuardedFallback(dialect) && canUseNativeUpsert(dialect.upsertStyle())) {
                MutationResult nativeResult = nativeUpsert(entity, ignoreNull, pkVal, hasListeners, dialect);
                if (nativeResult != null) {
                    return nativeResult;
                }
            }
        }
        boolean exists = (pkVal instanceof Serializable s) && finder.existsById(s);
        if (!exists) {
            return writer.insert(entity, ignoreNull);
        }
        MutationResult updated = writer.updateByPrimaryKey(entity, ignoreNull);
        return MutationResult.ofInsert(updated.affectedRows(), pkVal);
    }

    /**
     * 单条原生 upsert。返回 {@code null} 表示方言/数据不满足条件，调用方回退 select-then-write。
     */
    private MutationResult nativeUpsert(T entity, boolean ignoreNull, Object pkVal, boolean hasListeners, Dialect dialect) {
        String pk = meta.primaryKey();
        if (pk == null) {
            return null;
        }
        boolean exists = false;
        if (hasListeners) {
            exists = finder.existsById((Serializable) pkVal);
            if (exists) {
                lifecycle.preUpdate(entity);
            } else {
                lifecycle.prePersist(entity);
            }
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, entity).values();
        List<String> columns = new ArrayList<>();
        for (String column : meta.insertableColumnsWithPrimaryKey()) {
            if (ignoreNull && values.get(column) == null && !column.equals(pk)) {
                continue;
            }
            columns.add(column);
        }
        if (columns.isEmpty()) {
            return null;
        }
        Set<String> declared = new java.util.LinkedHashSet<>(columns);
        MutationContext ctx = new MutationContext(meta, values, null, declared, ignoreNull, false,
                kernel.namingPolicy());
        SqlStatement upsert = UpsertStatementFactory.create(ctx, columns, pk, dialect.upsertStyle(), dialect.dbType());
        int affected = kernel.update(upsert);
        if (hasListeners) {
            if (exists) {
                lifecycle.postUpdate(entity);
            } else {
                lifecycle.postPersist(entity);
            }
        }
        return MutationResult.ofInsert(affected, pkVal);
    }

    private MutationResult nativeBatchGroups(List<T> rows,
                                             String pk,
                                             boolean ignoreNull,
                                             int batchSize,
                                             EntityLifecycle lifecycle,
                                             Set<Object> existing,
                                             Dialect dialect) {
        if (lifecycle != null) {
            for (T row : rows) {
                boolean exists = existing.contains(normalizePrimaryKey(meta.readFieldValue(row, pk)));
                if (exists) {
                    lifecycle.preUpdate(row);
                } else {
                    lifecycle.prePersist(row);
                }
            }
        }
        Map<List<String>, List<T>> groups = new LinkedHashMap<>();
        for (T row : rows) {
            Map<String, Object> values = EntitySnapshot.snapshot(meta, row).values();
            List<String> columns = new ArrayList<>();
            for (String column : meta.insertableColumnsWithPrimaryKey()) {
                if (ignoreNull && values.get(column) == null && !column.equals(pk)) {
                    continue;
                }
                columns.add(column);
            }
            groups.computeIfAbsent(List.copyOf(columns), key -> new ArrayList<>()).add(row);
        }
        MutationResult total = MutationResult.EMPTY;
        for (Map.Entry<List<String>, List<T>> entry : groups.entrySet()) {
            MutationResult part = nativeBatch(entry.getValue(), pk, entry.getKey(), batchSize, dialect);
            if (part == null) {
                return null;
            }
            if (lifecycle != null) {
                for (T row : entry.getValue()) {
                    boolean exists = existing.contains(normalizePrimaryKey(meta.readFieldValue(row, pk)));
                    if (exists) {
                        lifecycle.postUpdate(row);
                    } else {
                        lifecycle.postPersist(row);
                    }
                }
            }
            total = total.merge(part);
        }
        return total;
    }

    private MutationResult nativeBatch(List<T> rows, String pk, List<String> columns, int batchSize, Dialect dialect) {
        List<Object[]> batchRows = new ArrayList<>(rows.size());
        for (T row : rows) {
            Map<String, Object> values = EntitySnapshot.snapshot(meta, row).values();
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                args[i] = values.get(columns.get(i));
            }
            batchRows.add(args);
        }
        BatchSqlStatement upsertBatch = UpsertStatementFactory.createBatch(
                meta, columns, batchRows, kernel.namingPolicy(), batchSize, pk, dialect.upsertStyle(), dialect.dbType());
        BatchSlice slice = kernel.batchExecute(upsertBatch);
        return MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    MutationResult saveAll(Collection<T> entities, boolean ignoreNull) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<T> rows = entities instanceof List<T> l ? l : new ArrayList<>(entities);
        String pk = writer.requirePrimaryKey();
        List<Object> primaryKeys = new ArrayList<>(rows.size());
        Set<Object> seenPrimaryKeys = new HashSet<>();
        for (T row : rows) {
            Objects.requireNonNull(row, "Entity element in saveAll must not be null");
            Object pkVal = meta.readFieldValue(row, pk);
            Object normalizedPk = normalizePrimaryKey(pkVal);
            primaryKeys.add(normalizedPk);
            if (normalizedPk != null && !seenPrimaryKeys.add(normalizedPk)) {
                throw new IllegalArgumentException(
                        "Duplicate primary key [" + pkVal + "] in saveAll batch for entity "
                                + meta.entityType().getName());
            }
        }

        int configuredBatchSize = kernel.executionOptions().batchSize();
        int batchPartitionSize = configuredBatchSize > 0 ? configuredBatchSize : ExecutionOptions.DEFAULT_BATCH_SIZE;

        boolean hasListeners = rows.stream().anyMatch(lifecycle::hasListenersFor);
        if (primaryKeys.stream().allMatch(Objects::nonNull)) {
            Dialect dialect = kernel.dialect();
            if (!requiresGuardedFallback(dialect) && canUseNativeUpsert(dialect.upsertStyle())) {
                Set<Object> existing = hasListeners
                        ? findExistingIds(kernel, meta, pk, primaryKeys)
                        : Set.of();
                MutationResult nativeBatch = nativeBatchGroups(rows, pk, ignoreNull,
                        batchPartitionSize, hasListeners ? lifecycle : null, existing, dialect);
                if (nativeBatch != null) {
                    return nativeBatch;
                }
            }
        }

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
                total = total.merge(ignoreNull ? writer.patchAll(toUpdate) : writer.updateAll(toUpdate));
            }
            if (!toInsert.isEmpty()) {
                total = total.merge(writer.insertAll(toInsert, ignoreNull));
            }
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
        List<Object> keyList = keys instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(keys);
        Set<Object> existing = new HashSet<>(keyList.size());

        for (List<Object> chunk : Partitions.of(keyList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            WhereCriterion where = WhereCriterion.create();
            where.in(pk, chunk);
            SqlStatement stmt = SelectStatementFactory.create(meta, where, Set.of(pk), kernel.namingPolicy());
            List<Object> rows = kernel.queryList(stmt, rs -> {
                try {
                    Object raw = rs.getObject(1);
                    if (raw != null && pkType != null && !pkType.isInstance(raw)) {
                        raw = ConvertUtils.convert(raw, pkType);
                    }
                    return normalizePrimaryKey(raw);
                } catch (SQLException e) {
                    throw new DbException(e);
                }
            });
            existing.addAll(rows);
        }
        return existing;
    }

    /**
     * UPSERT 同时隐含 INSERT 与 UPDATE 语义，且回退路径必须先做 SELECT 存在性探测，
     * 因此三者只要任一操作可能被改写，就必须回退到受行级 WHERE 保护的路径。
     */
    private static final Set<Operation> UPSERT_GUARD_OPERATIONS =
            Set.of(Operation.SELECT, Operation.INSERT, Operation.UPDATE);

    /**
     * 是否必须回退到受行级 WHERE 保护的 select-then-write 路径。
     *
     * <p>判定口径与 {@code saveAll} 的既有门禁一致，但不再按「是否注册了任意改写器」一刀切：
     * 只检查本实体、本表、{@code SELECT/INSERT/UPDATE} 三种操作是否有改写器声明支持。
     * 这样「只处理其他实体或其他操作」的改写器不会让全库原生批量 UPSERT 失效。
     */
    private boolean requiresGuardedFallback(Dialect dialect) {
        if (!kernel.hasRewriters()) {
            return false;
        }
        Set<TableRef> tables = Set.of(TableRef.ofQualified(meta.qualifiedTableName(kernel.namingPolicy()), ""));
        return kernel.hasApplicableRewriters(
                UPSERT_GUARD_OPERATIONS, meta.entityType(), tables, dialect.dbType());
    }

    private boolean canUseNativeUpsert(UpsertStyle style) {
        if (style == UpsertStyle.NONE) {
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

    private static Object normalizePrimaryKey(Object key) {
        if (key instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        return key;
    }
}
