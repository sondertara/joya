package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface JoyaMapper<T> {

    /**
     * Saves a given entity. Use the returned instance for further operations as the save operation might have changed the
     * entity instance completely.
     *
     * @param entity must not be {@literal null}.
     * @return the saved entity; will never be {@literal null}.
     * @throws IllegalArgumentException in case the given {@literal entity} is {@literal null}.
     */
    Serializable save(T entity);


    /*
     * (non-Javadoc)
     * @see org.springframework.data.repository.CrudRepository#save(java.lang.Iterable)
     */

    <S extends T> void saveAll(Iterable<S> entities);

    /*========================================select==============================================*/

    /**
     * Retrieves an entity by its id.
     *
     * @param id must not be {@literal null}.
     * @return the entity with the given id or {@literal Optional#empty()} if none found.
     * @throws IllegalArgumentException if {@literal id} is {@literal null}.
     */
    Optional<T> findById(Serializable id);

    /*
     * (non-Javadoc)
     * @see org.springframework.data.repository.CrudRepository#findAll()
     */

    List<T> findAll();

    List<T> findAll(OrderParam... orderParam);



    /*
     * (non-Javadoc)
     * @see org.springframework.data.repository.CrudRepository#findAll(java.lang.Iterable)
     */

    List<T> findAllById(Collection<?> ids);


    List<T> findListByField(IGetter<T, ?> fn, Object value);

    List<T> findListByCondition(UnaryOperator<WhereCriterion> func);

    T findByField(IGetter<T, ?> fn, Object value);

    T findByCondition(UnaryOperator<WhereCriterion> func);


    PageResult<T> queryPage(PageQueryParam queryParam);

    /**
     * Returns whether an entity with the given id exists.
     *
     * @param id must not be {@literal null}.
     * @return {@literal true} if an entity with the given id exists, {@literal false} otherwise.
     * @throws IllegalArgumentException if {@literal id} is {@literal null}.
     */
    boolean existsById(Serializable id);


    /**
     * Returns the number of entities available.
     *
     * @return the number of entities.
     */
    long count();


    /*========================================delete==============================================*/


    /**
     * Deletes the entity with the given id.
     *
     * @param id must not be {@literal null}.
     * @throws IllegalArgumentException in case the given {@literal id} is {@literal null}
     */
    void deleteById(Serializable id);

    /**
     * Deletes a given entity.
     *
     * @param entity must not be {@literal null}.
     * @throws IllegalArgumentException in case the given entity is {@literal null}.
     */
    void delete(T entity);

    /**
     * Deletes all instances of the type {@code T} with the given IDs.
     *
     * @param ids must not be {@literal null}. Must not contain {@literal null} elements.
     * @throws IllegalArgumentException in case the given {@literal ids} or one of its elements is {@literal null}.
     * @since 2.5
     */
    void deleteAllById(Iterable<? extends Serializable> ids);

    /**
     * Deletes the given entities.
     *
     * @param entities must not be {@literal null}. Must not contain {@literal null} elements.
     * @throws IllegalArgumentException in case the given {@literal entities} or one of its entities is {@literal null}.
     */
    void deleteAll(Iterable<? extends T> entities);

    /**
     * Deletes all entities managed by the repository.
     */
    void deleteAll();


    /**
     * truncate the table
     */
    void truncate();


}
