package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.PageQueryParams;
import com.sondertara.joya.core.result.MutationResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * {@link JoyaMapper} 默认实现 —— 直接委托 {@link DataOps#of(Class)} 与 {@link DataOps#from(Class)}。
 *
 * @param <T> 实体类型
 */
public class JoyaMapperImpl<T> implements JoyaMapper<T> {

    private final Class<T> entityClass;
    private final DataOps client;

    public JoyaMapperImpl(DataOps client, Class<T> entityClass) {
        this.client = client;
        this.entityClass = entityClass;
    }

    private EntityOps<T> ops() {
        return client.of(entityClass);
    }

    @Override
    public Serializable save(T entity) {
        MutationResult res = ops().save(entity);
        return res.getGeneratedKey(Serializable.class);
    }

    @Override
    public <S extends T> void saveAll(Iterable<S> entities) {
        if (entities == null) {
            return;
        }
        List<T> list = new ArrayList<>();
        for (S e : entities) {
            list.add(e);
        }
        ops().saveAll(list);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> MutationResult insertAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        return ops().insertAll((Collection<T>) entities);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> MutationResult updateAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        return ops().updateAll((Collection<T>) entities);
    }

    @Override
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final <S extends T> MutationResult updateAll(Collection<S> entities, IGetter<T, ?>... columns) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        return ops().updateAll((Collection<T>) entities, columns);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends T> MutationResult patchAll(Collection<S> entities) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        return ops().patchAll((Collection<T>) entities);
    }

    @Override
    public Optional<T> findById(Serializable id) {
        return ops().findById(id);
    }

    @Override
    public List<T> findAll() {
        return ops().findAll();
    }

    @Override
    public List<T> findAll(OrderParam... orderParam) {
        return ops().findAll(orderParam);
    }

    @Override
    public List<T> findListByField(IGetter<T, ?> fn, Object value) {
        return client.from(entityClass).where(w -> w.eq(fn, value)).list();
    }

    @Override
    public List<T> findListByCondition(UnaryOperator<WhereCriterion> func) {
        return client.from(entityClass).where(func).list();
    }

    @Override
    public T findByCondition(UnaryOperator<WhereCriterion> func) {
        return client.from(entityClass).where(func).one().orElse(null);
    }

    @Override
    public T findByField(IGetter<T, ?> fn, Object value) {
        return client.from(entityClass).where(w -> w.eq(fn, value)).one().orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<T> findAllById(Collection<?> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ops().findByIds((Collection<? extends Serializable>) ids);
    }

    @Override
    public PageResult<T> queryPage(PageQueryParam queryParam) {
        return client.from(entityClass)
                .apply(PageQueryParams.of(queryParam))
                .page(queryParam.toPageRequest());
    }

    @Override
    public boolean existsById(Serializable id) {
        return ops().existsById(id);
    }

    @Override
    public long count() {
        return ops().count();
    }

    @Override
    public void deleteById(Serializable id) {
        ops().deleteById(id);
    }

    @Override
    public void delete(T entity) {
        ops().delete(entity);
    }

    @Override
    public void deleteAllById(Iterable<? extends Serializable> ids) {
        if (ids == null) {
            return;
        }
        List<Serializable> list = new ArrayList<>();
        for (Serializable id : ids) {
            list.add(id);
        }
        ops().deleteByIds(list);
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        if (entities == null) {
            return;
        }
        List<T> list = new ArrayList<>();
        for (T entity : entities) {
            list.add(entity);
        }
        ops().deleteAll(list);
    }

    @Override
    public void deleteAll() {
        ops().delete().allowAll().execute();
    }

    @Override
    public void truncate() {
        ops().truncate();
    }
}