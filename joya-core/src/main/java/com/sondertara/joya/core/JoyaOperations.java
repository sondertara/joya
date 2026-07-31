package com.sondertara.joya.core;

import com.sondertara.common.model.PageResult;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.model.PersistEntity;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import com.sondertara.joya.core.query.pagination.PageParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;

import java.io.Serializable;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * the jdbc operations
 *
 * @author huangxiaohu
 */
public interface JoyaOperations {




    /**
     * 根据条件查询多条记录: 例（and a.name = ?）
     */
    <T> List<T> findList(Class<T> entityClass, UnaryOperator<WhereCondition<T>> func);

    <T> T find(Class<T> entityClass, UnaryOperator<WhereCondition<T>> func);

    <T> List<T> findTop(Class<T> entityClass, int topNum, UnaryOperator<WhereCondition<T>> func);

    /**
     * 根据多个主键查询多条记录
     */
    <T> List<T> findAllById(Class<T> entityClass, Collection<?> keys);

    <T, R> List<R> findIdExists(Class<T> entityClass, Collection<R> keys);


    /**
     * 根据主键查询一条记录
     */
    <T> T findById(Class<T> entityClass, Serializable key);


    /************************************************  条件构造器  *****************************************************/

    /**
     * 条件构造器查询-分页查询
     */
    <T> PageResult<T> queryPage(NativeSqlQuery nativeSqlQuery, Class<T> tClass, PageParam page);

    <T> PageResult<T> queryPage(NativeSqlQuery nativeSqlQuery, RowMapper<T> rowMapper, PageParam page);


    <T> PageResult<T> queryPage(PageQueryParam queryParam, RowMapper<T> rowMapper);

    /**
     * 条件构造器查询-查询多个
     */
    <T> List<T> findList(NativeSqlQuery nativeSqlQuery, Class<T> tClass);


    /**
     * 条件构造器查询-查询单个对象
     */
    <T> T find(NativeSqlQuery nativeSqlQuery, Class<T> tClass);

    /**
     * 条件构造器查询-查询数量
     */
    <T> long count(NativeSqlQuery nativeSqlQuery);


    /**
     * 查询单条记录，!= null 的实体属性即为条件(全等查询)
     */
    <T> T query(T entity);

    /**
     * 查询多条记录，!= null 的实体属性即为条件(全等查询)
     */
    <T> List<T> findList(T entity);




    /* ----------------------------------------------------------------delete---------------------------------------------------------------- */

    /**
     * 根据主键删除一条记录
     */
    <T> int deleteById(Class<T> t, Serializable key);

    /**
     * 根据主键删除多条记录
     */
    <T> int deleteAllById(Class<T> t, Iterable<? extends Serializable> keys);

    <T> void deleteEntity(T entity);

    /**
     * 根据条件删除记录
     */
    <T> int deleteByCondition(Class<T> t, UnaryOperator<WhereCondition<T>> func);


    /* ----------------------------------------------------------------insert---------------------------------------------------------------- */



    /**
     * 插入一条记录(默认在实体中set新的主键)
     * @return 对象
     */
    <T> Object insertEntity(T entity);

    <T> Object insertEntityIgnoreNull(T entity);

    <T> void insertAll(List<T> dataList);


    /* ----------------------------------------------------------------update---------------------------------------------------------------- */

    /**
     * 批量更新
     *
     * @param dataList
     * @param <T>
     */
    <T> void updateAll(List<T> dataList);




    /**
     * 通过where条件更新，更新字段放在entity中
     *
     * @param entity 更新字段内容
     * @param func   where参数
     * @param <T>    entity class
     * @return
     */
    <T> int updateEntityByCondition(T entity, UnaryOperator<WhereCondition<T>> func);

    /**
     * 更新记录
     *
     * @param entity
     * @param <T>
     */
    <T> void updateEntity(T entity);

    /**
     * 保存忽略空值
     *
     * @param entity
     * @param <T>
     */
    <T> void updateEntityIgnoreNull(T entity);


    /* ----------------------------------------------------------------common---------------------------------------------------------------- */

    /**
     * 保存一条记录（根据主键添加或修改）
     */
    <T> Object saveEntity(T entity);

    <T> Object saveEntityIgnoreNull(T entity);

    /**
     * Batch update, first execute the update operation, then the insert operation
     * <p>
     * it is not performed in the transaction by default
     * if you want to perform with transaction ,you should open a trx before batch save
     * and call commit after batch update finished, and do not forget to close the sql connection!!!!!
     * <p>
     * 批量更新，先执行更新操作,后执行插入操作 默认不支持事务
     * 如果需要在事务中执行，请手动显示调用开启事务，批量保存结束后手动提交事务 并关闭 sql connection!!!!!!!!!
     * <p>
     * The pojo if extends {@link PersistEntity},and it will explicitly specify whether it is new by the field isNew
     * 实体如果继承{@link PersistEntity} 通过isNew字段来表示是否是新建还是更新，默认是新增
     * <p>
     * 1.对于mysql主键自增这种实体通过重写{@link PersistEntity#isNew()}判断主键是否为null就可以标识是否新增
     * 2.对于通过序列来设置主键值的情形,在构造dataList时,需要显示调用{@link  PersistEntity#setNew(boolean)} 来标识是否新增
     * <p>
     * If the pojo is normal entity, then will  check if the record is already persisted at the database by the primary key
     * 实体如果是普通Pojo,对主键有值的对象会判断是否存在数据库中,进而判断是否为更新或插入操作
     *
     * @param dataList 数据
     * @param <T>      类型
     */
    <T> void saveAll(List<T> dataList);


    /**
     * 删除表
     */
    void dropTables(Class<?>... arr) throws SQLFeatureNotSupportedException;

    /**
     * 创建表
     */
    void createTables(Class<?>... arr) throws SQLFeatureNotSupportedException;

}
