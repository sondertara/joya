package com.sondertara.joya.jpa.repository;

import com.sondertara.joya.core.query.NativeSqlQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.lang.NonNull;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * <p>抽象DAO层基类 提供一些简便方法<br/>
 * 泛型 ： T 表示实体类型；ID表示主键类型
 * <p/>
 *
 * @author huangxiaohu
 */
@NoRepositoryBean
public interface BaseRepository<T, ID extends Serializable> extends PagingAndSortingRepository<T, ID>/*, JpaSpecificationExecutor<T>*/ {

    Class<T> getEntityClass();

    String getEntityName();


    /**
     * 使用SQL查询列表，数据已map形式封装
     *
     * @param querySql
     * @param params
     * @return
     */
    List<Map<String, Object>> findMapListByHql(String querySql, final Map<String, ?> params);

    /**
     * 使用SQL查询对象
     *
     * @param querySql sql
     * @param <X>      type
     * @param clazz    target class
     * @param params   query params
     * @return object
     */
    <X> X findObjectBySql(String querySql, Class<X> clazz, final Map<String, ?> params);

    /**
     * 查询单个对象
     *
     * @param hql    hql
     * @param params params
     * @return Object
     */
    <X> X findObjectByHql(String hql, final Map<String, ?> params);

    /**
     * 使用SQL查询对象，数据已map形式封装
     *
     * @param querySql
     * @param params
     * @return
     */
    public Map<String, Object> findMapBySql(String querySql, final Map<String, ?> params);

    /**
     * 执行SQL语句
     *
     * @param sql
     * @param params
     * @return
     */
    public int executeSql(String sql, final Map<String, ?> params);


    /**
     * 根据查询SQL，返回对象列表
     *
     * @param querySql 查询语句
     * @param params   参数MAP格式
     * @return
     */
    List<Object> findListBySql(String querySql, Map<String, ?> params);


    /**
     * save
     *
     * @param s   s
     * @param <S> s
     * @return s
     */
    @NonNull
    <S extends T> S saveIgnoreNull(@NonNull S s);


    /**
     * batch delete
     *
     * @param ids id list
     */
    void deleteById(Iterable<ID> ids);

    /**
     * entity manger all flush
     */
    void flush();

    /**
     * save and flush
     *
     * @param s   entity
     * @param <S> entity
     * @return entity
     */
    <S extends T> S saveAndFlush(S s);


    /**
     * delete all
     */
    void deleteAllInBatch();


    /**
     * 查询map
     *
     * @return map
     */
    Map<ID, T> mGetAll();

    /**
     * get as map
     *
     * @param ids id list
     * @return map
     */
    Map<ID, T> mGetAllById(Iterable<ID> ids);

    /**
     * query by sql
     *
     * @param sql    sql
     * @param clazz  target
     * @param params params
     * @return List
     */
    <X> List<X> findListBySql(String sql, Class<X> clazz, Object... params);

    /**
     * 根据Hql查询列表
     *
     * @param queryJql
     * @param params
     * @return
     */
    <X> X findListByHql(String queryJql, final Map<String, ?> params);

    /**
     * query by native sql query
     *
     * @param nativeSql   query
     * @param resultClass result class
     * @param <X>         target
     * @return list
     */
    <X> List<X> findListBySql(NativeSqlQuery nativeSql, Class<X> resultClass);

    /**
     * 查询分页对象
     *
     * @param nativeSql   native query
     * @param resultClass result bean
     * @param pageNo      pageNo minimum is 1
     * @param pageSize    pageSize
     * @return Page
     */
    <X> Page<X> queryPageBySql(NativeSqlQuery nativeSql, Class<X> resultClass, Integer pageNo, Integer pageSize);

    /**
     * 获取数量 by hql
     *
     * @param hql
     * @param params
     * @return
     */
    long countByHql(final String hql, final Object... params);

}
