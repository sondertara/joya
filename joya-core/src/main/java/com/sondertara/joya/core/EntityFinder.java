package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 实体读取器 —— 只负责"按主键/集合/条件读"，不参与任何写入。
 *
 * <p>从 {@code BatchPlanner} 中拆出：批量写规划器不应出现 {@code find} 语义。
 */
final class EntityFinder<T> {

    private final JoyaTemplate template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;
    private final String primaryKey;

    EntityFinder(JoyaTemplate template, Class<T> entityType, EntityMetadataContext meta, String primaryKey) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        this.primaryKey = primaryKey;
    }

    Optional<T> findById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        requirePrimaryKey();
        SqlStatement stmt = SelectStatementFactory.byPrimaryKey(meta, id, template.namingPolicy());
        return Optional.ofNullable(template.query(stmt, BeanRowMapper.of(entityType)));
    }

    List<T> findByIds(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() <= Partitions.MAX_IN_CLAUSE_SIZE) {
            return queryByIds(ids);
        }
        List<T> result = new ArrayList<>(ids.size());
        List<Serializable> idList = new ArrayList<>(ids);
        for (List<Serializable> chunk : Partitions.of(idList, Partitions.MAX_IN_CLAUSE_SIZE)) {
            result.addAll(queryByIds(chunk));
        }
        return result;
    }

    List<T> findAll() {
        return new Query<>(template, entityType).list();
    }

    List<T> findAll(OrderParam... orderParams) {
        return new Query<>(template, entityType).orderBy(orderParams).list();
    }

    boolean existsById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        requirePrimaryKey();
        return new Query<>(template, entityType).where(w -> w.eq(primaryKey, id)).exists();
    }

    long count() {
        return new Query<>(template, entityType).count();
    }

    private List<T> queryByIds(Collection<? extends Serializable> ids) {
        requirePrimaryKey();
        return new Query<>(template, entityType).where(w -> w.in(primaryKey, ids)).list();
    }

    private void requirePrimaryKey() {
        if (primaryKey == null || primaryKey.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + entityType.getName());
        }
    }
}
