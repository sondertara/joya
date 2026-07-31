package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.statment.creator.SelectSqlCreator;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import com.sondertara.joya.core.query.pagination.JoyaPageConvert;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.utils.Lists;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public class SimpleJoyaMapper<T> implements JoyaMapper<T> {
    private final Class<T> entityClass;

    private final JoyaJdbc joyaJdbc;

    private final TableMetaContext entityContext;

    public SimpleJoyaMapper(JoyaJdbc joyaJdbc, Class<T> entityClass) {

        this.entityClass = entityClass;
        this.joyaJdbc = joyaJdbc;
        this.entityContext = TableMetaContext.of(entityClass);
    }


    @Override
    public Serializable save(T entity) {
        return (Serializable) getJoyaJdbc().saveEntity(entity);
    }


    private JoyaJdbc getJoyaJdbc() {
        return this.joyaJdbc;
    }

    @Override
    public <S extends T> void saveAll(Iterable<S> entities) {

        getJoyaJdbc().saveAll(Lists.newArrayList(entities));
    }

    @Override
    public Optional<T> findById(Serializable id) {


        SelectSqlCreator<T> creator = new SelectSqlCreator<>(entityContext);
        creator.where(w -> w.eq(entityContext.getPrimaryKeyName(), id));
        T row = getJoyaJdbc().querySingleRow(creator.create(), new BeanRowMapper<>(entityClass), creator.createParameters());
        return Optional.ofNullable(row);
    }

    @Override
    public List<T> findAll() {
        return getJoyaJdbc().findList(entityClass, null);
    }

    @Override
    public List<T> findAll(OrderParam... orderParam) {
        SelectSqlCreator<T> creator = new SelectSqlCreator<>(entityContext);
        if (orderParam == null || orderParam.length == 0) {
            throw new IllegalArgumentException("orderParam must not be null");
        }
        String sb = creator.create() + "ORDER BY ";

        StringJoiner sj = new StringJoiner(", ");
        for (OrderParam param : orderParam) {
            sj.add(param.getFieldName() + " " + param.getOrderType());
        }

        return getJoyaJdbc().queryList(sb, new BeanRowMapper<>(entityClass));
    }


    @Override
    public List<T> findListByField(IGetter<T, ?> fn, Object value) {
        return getJoyaJdbc().findList(entityClass, w -> w.eq(fn, value));
    }

    @Override
    public List<T> findListByCondition(UnaryOperator<WhereCondition<T>> func) {
        return getJoyaJdbc().findList(entityClass, func);
    }

    @Override
    public T findByCondition(UnaryOperator<WhereCondition<T>> func) {
        return getJoyaJdbc().find(entityClass, func);
    }

    @Override
    public T findByField(IGetter<T, ?> fn, Object value) {
        return getJoyaJdbc().find(entityClass, w -> w.eq(fn, value));
    }

    @Override
    public List<T> findAllById(Collection<?> ids) {
        return getJoyaJdbc().findAllById(entityClass, ids);
    }


    @Override
    public PageResult<T> queryPage(PageQueryParam queryParam) {
        NativeSqlQuery sqlQuery = JoyaPageConvert.create()
                .queryParam(queryParam)
                .from(entityClass)
                .build();

        return joyaJdbc.queryPage(sqlQuery, entityClass, PageParam.of(queryParam.getPage(), queryParam.getPageSize()));
    }

    @Override
    public boolean existsById(Serializable id) {
        Optional<T> optional = findById(id);
        return optional.isPresent();
    }

    @Override
    public long count() {
        return getJoyaJdbc().querySingleValue("SELECT count(*) FROM " + entityContext.getTableName(), Long.class);
    }

    @Override
    public void deleteById(Serializable id) {
        getJoyaJdbc().execute("DELETE FROM " + entityContext.getTableName() + " WHERE " + entityContext.getPrimaryKeyName() + " =?", new Object[]{id});

    }

    @Override
    public void delete(T entity) {
        getJoyaJdbc().deleteEntity(entity);

    }

    @Override
    public void deleteAllById(Iterable<? extends Serializable> ids) {
        getJoyaJdbc().deleteAllById(entityClass, ids);

    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    public void deleteAll() {
        getJoyaJdbc().updateEntity("DELETE FROM " + entityContext.getTableName());
    }


    @Override
    public void truncate() {
        getJoyaJdbc().updateEntity("TRUNCATE TABLE " + entityContext.getTableName());
    }
}
