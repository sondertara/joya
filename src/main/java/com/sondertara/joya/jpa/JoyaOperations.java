package com.sondertara.joya.jpa;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.common.model.PageResult;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.pagination.PageQueryParam;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;


/**
 * 基于JPA的ORM操作接口
 *
 * <p>
 *
 * @author huangxiaohu
 */
public interface JoyaOperations {

    /**
     * 查询list
     *
     * @param sql         原生sql
     * @param resultClass 目标实体
     * @param params      参数
     * @param <T>         泛型
     * @return list
     */
    <T> List<T> findListBySql(String sql, Class<T> resultClass, Object... params);

    /**
     * find to list
     *
     * @param nativeSql   native query
     * @param resultClass result class
     * @param <T>         generic
     * @return list
     */
    <T> List<T> findListBySql(NativeSqlQuery nativeSql, Class<T> resultClass);

    /**
     * 根据给定标识和实体类返回持久化对象的实例，如果没有符合条件的持久化对象实例则返回null。
     *
     * @param entityClass 要加载的实体类
     * @param id          实体对象在数据表里的主键,该参数的实例类型必须符合对应持久对象的'ID'对应类型,否则无法执行查询.
     * @return 返回符合条件的持久对象
     */
    <T> T find(Class<T> entityClass, final Object id);


    /**
     * 根据给定字段条件进行查询,返回所有满足条件的结果集
     *
     * @param entityFunc 要查询的实体类
     * @param value      查询条件中对应字段的值
     * @return 查询结果集合
     */
    <T> List<T> findListByField(TaraFunction<T, ?> entityFunc, Object value);


    /**
     * 根据给定字段条件进行查询,返回所有唯一满足条件的记录
     * <p/>
     * [注]:如果查询结果集中有多个实体满足条件,则抛出运行期异常.
     *
     * @param entityFunc 要查询的实体类
     * @param value      查询条件中对应字段的值
     * @return 唯一的查询结果记录
     */
    <T> T findByField(TaraFunction<T, ?> entityFunc, Object value);

    /**
     * 根据给定实体属性过滤条件进行查询,返回所有满足条件的实体总数
     *
     * @param entityFunc 要查询的实体类
     * @param value      value
     * @return 统计结果
     */
    <T> int countByField(TaraFunction<T, ?> entityFunc, Object value);

    /**
     * 根据给定字段过滤条件进行查询,返回唯一满足条件的实体对象
     * <p>
     * [注]:如果查询结果集中有多个实体满足条件,则抛出运行期异常.
     *
     * @param entityClass 要查询的实体类
     * @param params      field
     * @return 成功找到的结果对象
     */
    <T> T findByFields(Class<T> entityClass, Map<String, ?> params);


    /**
     * 根据给定字段过滤条件进行查询,返回所有满足条件的实体集合
     *
     * @param entityClass 要查询的实体类
     * @param params      查询条件字段-值MAP
     * @return 查询结果
     */
    <T> List<T> findListByFields(Class<T> entityClass, Map<String, ?> params);

    /**
     * 根据给定实体属性过滤条件进行查询,返回所有满足条件的实体总数
     *
     * @param entityClass 要查询的实体类
     * @param params      查询条件字段-值MAP
     * @return 统计结果
     */
    int countByFields(Class<?> entityClass, Map<String, ?> params);

    /**
     * find map list from table rows
     *
     * @param nativeSql sql
     * @param params    params
     * @param camelCase parse key to camelCase
     * @return list
     */
    List<Map<String, Object>> findMapListBySql(NativeSqlQuery nativeSql, List<Object> params, boolean camelCase);

    /**
     * find map from table rows
     *
     * @param nativeSql sql
     * @param params    params
     * @param camelCase parse key to camelCase
     * @return list
     */
    Map<String, Object> findMapBySql(NativeSqlQuery nativeSql, List<Object> params, boolean camelCase);

    /**
     * (non-Javadoc)
     */

    List<Map<String, Object>> findMapListBySql(String querySql, List<Object> params, boolean camelCase);


    Map<String, Object> findMapBySql(String querySql, List<Object> params, boolean camelCase);

    /**
     * query page
     *
     * @param resultClass the result class
     * @param queryParam  query params
     * @param targetClass the target query table
     * @param <T>         the type of result
     * @return pagination result
     */
    <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, Class<?>... targetClass);

    /**
     * query page
     *
     * @param resultClass the result class
     * @param queryParam  query params
     * @param joinPart    the join  table
     * @param <T>         the type of result
     * @return pagination result
     */
    <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, UnaryOperator<JoinCriterion> joinPart);


    /**
     * query page
     *
     * @param sql         sql query
     * @param resultClass result class
     * @param pageNo      page start
     * @param pageSize    page size
     * @param <T>         the type of result
     * @param params      the query params
     * @return pagination result
     */

    <T> PageResult<T> queryPage(String sql, Class<T> resultClass, Integer pageNo, Integer pageSize, Object... params);

    /**
     * query page
     *
     * @param nativeSql   sql query
     * @param resultClass result class
     * @param pageNo      page start
     * @param pageSize    page size
     * @param <T>         the type of result
     * @return pagination result
     */

    <T> PageResult<T> queryPage(NativeSqlQuery nativeSql, Class<T> resultClass, Integer pageNo, Integer pageSize);


}
