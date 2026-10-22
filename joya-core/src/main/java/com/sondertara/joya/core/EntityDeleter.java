package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.result.MutationResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 实体删除器 —— 只负责删除与清空，不参与查询与批量写规划。
 *
 * <p>从 {@code BatchPlanner} 与 {@code EntityOps} 中拆出：删除是独立的写语义，
 * 与"批量插入/更新"的分片、归组策略无关。
 */
final class EntityDeleter<T> {

    private final JoyaTemplate template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final String primaryKey;

    EntityDeleter(JoyaTemplate template, Class<T> entityType, EntityMetadataContext meta, String primaryKey) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.primaryKey = primaryKey;
    }

    MutationResult deleteById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        requirePrimaryKey();
        return new EntityDelete<T>(template, meta).where(w -> w.eq(primaryKey, id)).execute();
    }

    MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        requirePrimaryKey();
        if (ids == null || ids.isEmpty()) {
            return MutationResult.EMPTY;
        }
        if (ids.size() <= Partitions.MAX_IN_CLAUSE_SIZE) {
            return new EntityDelete<T>(template, meta).where(w -> w.in(primaryKey, ids)).execute();
        }
        long total = 0L;
        List<Serializable> idList = new ArrayList<>(ids);
        for (List<Serializable> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            total += new EntityDelete<T>(template, meta)
                    .where(w -> w.in(primaryKey, chunk))
                    .execute()
                    .affectedRows();
        }
        return MutationResult.ofAffected(total);
    }

    MutationResult delete(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        requirePrimaryKey();
        Object pkValue = meta.readFieldValue(entity, primaryKey);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for delete: " + entityType.getName());
        }
        return new EntityDelete<T>(template, meta).where(w -> w.eq(primaryKey, pkValue)).execute();
    }

    MutationResult deleteAll(Collection<? extends T> entities) {
        requirePrimaryKey();
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<Serializable> ids = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Object pkValue = meta.readFieldValue(entity, primaryKey);
            if (pkValue != null) {
                ids.add(pkValue instanceof Serializable s ? s : pkValue.toString());
            }
        }
        return deleteByIds(ids);
    }

    private void requirePrimaryKey() {
        if (primaryKey == null || primaryKey.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + entityType.getName());
        }
    }

    void truncate() {
        String tableName = meta.qualifiedTableName(template.namingPolicy());
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.of(tableName))
                .entityType(entityType)
                .build();
        template.update(SqlStatement.of("TRUNCATE TABLE " + tableName, List.of(), metadata, false));
    }
}
