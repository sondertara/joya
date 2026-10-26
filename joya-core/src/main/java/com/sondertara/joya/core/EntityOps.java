package com.sondertara.joya.core;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 类型化实体域门面 —— 由 {@link DataOps#of(Class)} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityOps<T> {

    private final JoyaTemplate template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final EntityFinder<T> finder;
    private final EntityDeleter<T> deleter;

    public EntityOps(JoyaTemplate template, Class<T> entityType) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = CacheHolder.tableMetaCache().getMetadataContext(entityType);
        this.finder = new EntityFinder<>(template, entityType, meta, meta.primaryKey());
        this.deleter = new EntityDeleter<>(template, entityType, meta, meta.primaryKey());
    }

    public MutationResult insert(T entity) {
        return doInsert(entity, false);
    }

    public MutationResult insertIgnoreNull(T entity) {
        return doInsert(entity, true);
    }

    public MutationResult insertAll(Collection<T> entities) {
        return BatchPlanner.executeInsertAll(template, meta, entities, false);
    }

    public MutationResult insertAllIgnoreNull(Collection<T> entities) {
        return BatchPlanner.executeInsertAll(template, meta, entities, true);
    }

    public MutationResult update(T entity) {
        return doUpdateByPrimaryKey(entity, false);
    }

    public MutationResult patch(T entity) {
        return doUpdateByPrimaryKey(entity, true);
    }

    public MutationResult updateAll(Collection<T> entities) {
        String pk = requirePrimaryKey();
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        return BatchPlanner.executeUpdateAll(template, meta, entities, setColumns, pk);
    }

    @SafeVarargs
    public final MutationResult updateAll(Collection<T> entities, IGetter<T, ?>... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("At least one column must be specified for selective updateAll");
        }
        List<String> colNames = new ArrayList<>(columns.length);
        for (IGetter<T, ?> getter : columns) {
            colNames.add(resolveColumn(meta, Objects.requireNonNull(getter, "column getter must not be null")));
        }
        return updateAllByColumns(entities, colNames);
    }

    public MutationResult updateAllByColumns(Collection<T> entities, Collection<String> columns) {
        return BatchPlanner.executeUpdateAllByColumns(template, meta, entities, columns, requirePrimaryKey());
    }

    public MutationResult patchAll(Collection<T> entities) {
        return BatchPlanner.executePatchAll(template, meta, entities, requirePrimaryKey());
    }

    public MutationResult save(T entity) {
        return UpsertPlanner.executeUpsert(this, meta, entity, false);
    }

    public MutationResult saveIgnoreNull(T entity) {
        return UpsertPlanner.executeUpsert(this, meta, entity, true);
    }

    public MutationResult saveAll(Collection<T> entities) {
        return UpsertPlanner.executeSaveAll(this, template, meta, entities, false);
    }

    public MutationResult saveAllIgnoreNull(Collection<T> entities) {
        return UpsertPlanner.executeSaveAll(this, template, meta, entities, true);
    }

    public MutationResult deleteById(Serializable id) {
        return deleter.deleteById(id);
    }

    public MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        return deleter.deleteByIds(ids);
    }

    public MutationResult delete(T entity) {
        return deleter.delete(entity);
    }

    public MutationResult deleteAll(Collection<? extends T> entities) {
        return deleter.deleteAll(entities);
    }

    public void truncate() {
        deleter.truncate();
    }

    public EntityInsert<T> insert() {
        return new EntityInsert<>(template, meta);
    }

    public EntityUpdate<T> update() {
        return new EntityUpdate<>(template, meta);
    }

    public EntityDelete<T> delete() {
        return new EntityDelete<>(template, meta);
    }

    public Optional<T> findById(Serializable id) {
        return finder.findById(id);
    }

    public List<T> findByIds(Collection<? extends Serializable> ids) {
        return finder.findByIds(ids);
    }

    public List<T> findAll() {
        return finder.findAll();
    }

    public List<T> findAll(OrderParam... orderParams) {
        return finder.findAll(orderParams);
    }

    public boolean existsById(Serializable id) {
        return finder.existsById(id);
    }

    public long count() {
        return finder.count();
    }

    private MutationResult doInsert(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        if (entity instanceof EntityListener listener) {
            listener.prePersist(entity);
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, entity).values();
        String pk = meta.primaryKey();
        Object existingPkVal = pk == null ? null : values.get(pk);
        Set<String> declared = new LinkedHashSet<>(existingPkVal != null ? meta.insertableColumnsWithPrimaryKey() : meta.insertableColumns());
        MutationContext ctx = new MutationContext(meta, values, null, declared, ignoreNull, false, template.namingPolicy());
        var slice = template.insertExecute(InsertStatementFactory.create(ctx));
        Object generatedKey = slice.generatedKey();
        if (existingPkVal == null && generatedKey != null) {
            GeneratedKeyWriter.writeBackPrimaryKey(meta, entity, generatedKey);
        }
        if (entity instanceof EntityListener listener) {
            listener.postPersist(entity);
        }
        return MutationResult.ofInsert(slice.affectedRows(), generatedKey != null ? generatedKey : existingPkVal);
    }

    private MutationResult doUpdateByPrimaryKey(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = requirePrimaryKey();
        if (entity instanceof EntityListener listener) {
            listener.preUpdate(entity);
        }
        Map<String, Object> values = EntitySnapshot.snapshot(meta, entity).values();
        Object pkValue = values.get(pk);
        if (pkValue == null) {
            throw new IllegalArgumentException("Entity primary key must not be null for update: " + entityType.getName());
        }
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        MutationContext ctx = new MutationContext(meta, values, WhereCriterion.create().eq(pk, pkValue),
                new LinkedHashSet<>(setColumns), ignoreNull, false, template.namingPolicy());
        long affected = template.update(UpdateStatementFactory.create(ctx));
        if (entity instanceof EntityListener listener) {
            listener.postUpdate(entity);
        }
        return MutationResult.ofAffected(affected);
    }

    String requirePrimaryKey() {
        String pk = meta.primaryKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + entityType.getName());
        }
        return pk;
    }

    static <E> String resolveColumn(EntityMetadataContext meta, IGetter<E, ?> getter) {
        return meta.resolveColumn(getter);
    }

    static String resolveColumnName(EntityMetadataContext meta, String columnOrField) {
        return meta.resolveColumnName(columnOrField);
    }
}
