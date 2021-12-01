
package com.sondertara.joya.jpa.repository;


import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.PageResult;
import com.sondertara.joya.ext.JoyaSpringContext;
import com.sondertara.joya.hibernate.transformer.AliasToBeanTransformer;
import com.sondertara.joya.utils.JoyaPageUtil;
import com.sondertara.joya.utils.SqlUtils;
import org.hibernate.query.internal.NativeQueryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * dao
 *
 * @author huangxiaohu
 */
@Transactional(readOnly = true)
public class JoyaRepository {

    private static final Logger log = LoggerFactory.getLogger(JoyaRepository.class);

    private final EntityManager em;

    private static final String SQL_VIEW_SWITCH = "joya.sql-view";

    public JoyaRepository(EntityManager em) {
        this.em = em;
    }


    /**
     * 查询list
     * @param sql 原生sql
     * @param clazz 目标实体
     * @param params 参数
     * @param <T> 泛型
     * @return list
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findListBySql(String sql, Class<T> clazz, Object... params) {
        Query query = em.createNativeQuery(sql);
        setParameters(query, params);
        return query.unwrap(NativeQueryImpl.class).setResultTransformer(new AliasToBeanTransformer<T>(clazz)).list();
    }

    /**
     *  find to list
     * @param nativeSql native query
     * @param resultClass result class
     * @param <T> generic
     * @return list
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findListBySql(NativeSqlQuery nativeSql, Class<T> resultClass) {

        Query query = em.createNativeQuery(nativeSql.toSql());
        setParameters(query, nativeSql.getParams());
        return query.unwrap(NativeQueryImpl.class).setResultTransformer(new AliasToBeanTransformer<T>(resultClass)).list();
    }

    /**
     * 分页查询
     *
     * @param resultClass 查询结果接收class
     * @param queryParam  查询数据
     * @param targetClass 查询数据库表
     * @param <T>         泛型
     * @return 分页数据
     */
    public <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, Class<?>... targetClass) {
        NativeSqlQuery nativeSqlQuery = JoyaPageUtil.buildNativeQuery(queryParam, targetClass);
        return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());

    }

    /**
     * 分页查询
     *
     * @param resultClass 查询结果接收class
     * @param queryParam  查询数据
     * @param <T>         泛型
     * @param joinPart    join语句
     * @return 分页数据
     */
    public <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, UnaryOperator<JoinCriterion> joinPart) {

        NativeSqlQuery nativeSqlQuery = JoyaPageUtil.buildNativeQuery(queryParam, joinPart);
        return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());

    }

    /**
     * 通过sql 分页查询
     *
     * @param nativeSql   查询语句
     * @param resultClass 结果映射class
     * @param pageNo      page
     * @param pageSize    pageSize
     * @param <T>         result
     * @return result
     */
    @SuppressWarnings("unchecked")
    public <T> PageResult<T> queryPage(NativeSqlQuery nativeSql, Class<T> resultClass, Integer pageNo, Integer pageSize) {
        Boolean opened = JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, false);

        String sqlStr = nativeSql.toSql();

        if (opened) {
            log.info("query Page sql is \n{}", sqlStr);
        }

        //获取总记录数
        String countSql = SqlUtils.buildCountSql(sqlStr);
        Query countQuery = em.createNativeQuery(countSql);
        if (opened) {
            log.info("count sql is \n{}", countSql);
        }
        setParameters(countQuery, nativeSql.getParams());
        long totalRecord = ((Number) countQuery.getSingleResult()).longValue();

        //获取分页结果
        Query pageQuery = em.createNativeQuery(sqlStr);

        setParameters(pageQuery, nativeSql.getParams());


        List<T> result = totalRecord == 0 ? new ArrayList<T>(0) :
                pageQuery.setFirstResult(pageNo)
                        .setMaxResults(pageSize)
                        .unwrap(NativeQueryImpl.class)
                        .setResultTransformer(new AliasToBeanTransformer<T>(resultClass))
                        .list();

        return new PageResult<>(pageNo, pageSize, totalRecord, result);
    }


    /***********************************ql*************************************************/

    /**
     * 根据ql和按照索引顺序的params查询一个实体
     */
    public <T> T findOneByHql(final String hql, final Object... params) {
        List<T> list = findAllByHql(hql, PageRequest.of(0, 1), params);
        if (list.size() > 0) {
            return list.get(0);
        }
        return null;
    }

    public <T> List<T> findAllByHql(final String hql, final Object... params) {
        return findAllByHql(hql, (Pageable) null, params);
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，sort存储排序信息 null表示不排序
     *
     * @param ql
     * @param sort   null表示不排序
     * @param params List<Order> orders = this.find("SELECT o FROM Order o WHERE o.storeId = ? and o.code = ? order by o.createTime desc", storeId, code);
     * @return
     */
    public <T> List<T> findAllByHql(final String ql, final Sort sort, final Object... params) {
        Query query = em.createQuery(ql + prepareOrder(sort));
        setParameters(query, params);
        return query.getResultList();
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，pageable存储分页信息 null表示不分页
     *
     * @param ql
     * @param pageable null表示不分页
     * @param params
     * @return
     */
    public <T> List<T> findAllByHql(final String ql, final Pageable pageable, final Object... params) {
        Query query = em.createQuery(ql + prepareOrder(pageable != null ? pageable.getSort() : null));
        setParameters(query, params);
        if (pageable != null) {
            query.setFirstResult((int) pageable.getOffset());
            query.setMaxResults(pageable.getPageSize());
        }
        return query.getResultList();
    }

    /**
     * 根据ql和按照索引顺序的params执行ql统计
     */
    public long countByQL(final String ql, final Object... params) {
        Query query = em.createQuery(ql);
        setParameters(query, params);
        return (Long) query.getSingleResult();
    }


    /**
     * 拼排序
     */
    private String prepareOrder(Sort sort) {
        return (sort == null || !sort.iterator().hasNext()) ? "" :
                (" order by " + sort.toString().replace(":", " "));
    }

    /**
     * 按顺序设置Query参数
     */
    private void setParameters(Query query, Object[] params) {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
        }
    }

    /**
     * 按顺序设置Query参数
     */
    private void setParameters(Query query, List params) {
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                query.setParameter(i + 1, params.get(i));
            }
        }
    }
}
