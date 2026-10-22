package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ConvertUtils;

import java.io.Serializable;
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
 */
final class UpsertPlanner {

    private UpsertPlanner() {
    }

    static <T> MutationResult executeUpsert(
            EntityOps<T> ops,
            EntityMetadataContext meta,
            T entity,
            boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : meta.readFieldValue(entity, pk);
        boolean exists = (pkVal instanceof Serializable s) && ops.existsById(s);
        if (!exists) {
            return ignoreNull ? ops.insertIgnoreNull(entity) : ops.insert(entity);
        }
        MutationResult updated = ignoreNull ? ops.patch(entity) : ops.update(entity);
        return MutationResult.ofInsert(updated.affectedRows(), pkVal);
    }

    static <T> MutationResult executeSaveAll(
            EntityOps<T> ops,
            JoyaTemplate template,
            EntityMetadataContext meta,
            Collection<T> entities,
            boolean ignoreNull) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<T> rows = BatchPlanner.toList(entities);
        String pk = ops.requirePrimaryKey();
        int batchPartitionSize = BatchPlanner.effectiveBatchSize(template);
        MutationResult total = MutationResult.EMPTY;

        for (List<T> partition : Partitions.of(rows, batchPartitionSize)) {
            List<T> toInsert = new ArrayList<>();
            List<T> toUpdate = new ArrayList<>();
            Map<Object, T> keyed = new LinkedHashMap<>();

            for (T row : partition) {
                Objects.requireNonNull(row, "Entity element in saveAll must not be null");
                Object pkVal = meta.readFieldValue(row, pk);
                if (pkVal == null) {
                    toInsert.add(row);
                } else {
                    keyed.put(pkVal, row);
                }
            }
            if (!keyed.isEmpty()) {
                Set<Object> existing = findExistingIds(template, meta, pk, keyed.keySet());
                keyed.forEach((pkVal, row) -> {
                    if (existing.contains(pkVal)) {
                        toUpdate.add(row);
                    } else {
                        toInsert.add(row);
                    }
                });
            }
            if (!toUpdate.isEmpty()) {
                total = total.merge(ignoreNull ? ops.patchAll(toUpdate) : ops.updateAll(toUpdate));
            }
            if (!toInsert.isEmpty()) {
                total = total.merge(ignoreNull ? ops.insertAllIgnoreNull(toInsert) : ops.insertAll(toInsert));
            }
        }
        return total;
    }

    static Set<Object> findExistingIds(
            JoyaTemplate template,
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
            SqlStatement stmt = SelectStatementFactory.create(meta, where, Set.of(pk), null, template.namingPolicy());
            List<Object> rows = template.queryList(stmt, rs -> {
                try {
                    Object raw = rs.getObject(1);
                    if (raw != null && pkType != null && !pkType.isInstance(raw)) {
                        return ConvertUtils.convert(raw, pkType);
                    }
                    return raw;
                } catch (SQLException e) {
                    throw new DbException(e);
                }
            });
            existing.addAll(rows);
        }
        return existing;
    }
}
