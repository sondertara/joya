
package com.sondertara.joya.jpa.repository;


import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.hibernate.transformer.AliasToBeanTransformer;
import com.sondertara.joya.utils.BeanUtil;
import org.apache.commons.beanutils.BeanUtils;
import org.hibernate.Session;
import org.hibernate.query.internal.NativeQueryImpl;
import org.hibernate.transform.Transformers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.lang.NonNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * dao
 *
 * @author huangxiaohu
 */
@Transactional(readOnly = true)
public class BaseRepositoryImpl<T, ID extends Serializable> extends SimpleJpaRepository<T, ID> implements BaseRepository<T, ID> {

    private final JpaEntityInformation<T, ?> eif;
    private final EntityManager em;
    private final Class<T> entityClass;
    private String entityName;
    private String idName;

    private static final Logger log = LoggerFactory.getLogger(BaseRepositoryImpl.class);


    public BaseRepositoryImpl(JpaEntityInformation<T, ID> eif, EntityManager em) {
        super(eif, em);
        this.eif = eif;
        this.em = em;

        this.entityClass = eif.getJavaType();
        this.entityName = eif.getEntityName();
        this.idName = eif.getIdAttributeNames().iterator().next();
    }


    @Override
    public Class<T> getEntityClass() {
        return this.entityClass;
    }

    @Override
    public String getEntityName() {
        return entityName;
    }


    @Override
    @NonNull
    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public <S extends T> S saveIgnoreNull(@NonNull S entity) {
        if (this.eif.isNew(entity)) {
            this.em.persist(entity);
            return entity;
        } else {
            T po = super.findById(((ID) Objects.requireNonNull(eif.getId(entity)))).orElse(null);
            if (Objects.nonNull(po)) {
                BeanUtil.copyProperties(entity, po);
                return (S) this.em.merge(po);
            }
            return this.em.merge(entity);
        }
    }


    /**
     * 根据主键删除相应实体
     */
    @Override
    @Transactional(rollbackFor = Exception.class, readOnly = false)
    public void deleteById(Iterable<ID> ids) {
        if (ObjectUtils.isEmpty(ids)) {
            return;
        }
        List<T> models = new ArrayList<T>();
        for (ID id : ids) {
            T model;
            try {
                model = entityClass.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("batch delete " + entityClass + " error", e);
            }
            try {
                BeanUtils.setProperty(model, idName, id);
            } catch (Exception e) {
                throw new RuntimeException("batch delete " + entityClass + " error, can not set id", e);
            }
            models.add(model);
        }
        deleteInBatch(models);
    }


    /**
     * 查询,并封装到Map
     */
    @Override
    public Map<ID, T> mGetAll() {
        return toMap(findAll());
    }

    @Override
    public Map<ID, T> mGetAllById(Iterable<ID> ids) {
        return toMap(findAllById(ids));
    }

    private Map<ID, T> toMap(List<T> list) {
        Map<ID, T> result = new LinkedHashMap<>();
        for (T t : list) {
            if (t != null) {
                result.put((ID) eif.getId(t), t);
            }
        }
        return result;
    }

    /***********************************sql*************************************************/
    // select * where id = ?1 name = ?2 ...
    @Override
    @SuppressWarnings("unchecked")
    public <X> List<X> findListBySql(String sql, Class<X> clazz, Object... params) {
        Query query = em.unwrap(Session.class).createNativeQuery(sql);
        setParameters(query, params);
        return query.unwrap(NativeQueryImpl.class).setResultTransformer(new AliasToBeanTransformer<>(clazz)).list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> List<X> findListBySql(NativeSqlQuery nativeSql, Class<X> resultClass) {
        Query query = em.unwrap(Session.class).createNativeQuery(nativeSql.toSql());
        setParameters(query, nativeSql.getParams());
        return query.unwrap(NativeQueryImpl.class).setResultTransformer(new AliasToBeanTransformer<>(resultClass)).list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> Page<X> queryPageBySql(NativeSqlQuery nativeSql, Class<X> resultClass, Integer pageNo, Integer pageSize) {
        String sqlStr = nativeSql.toSql();

        //获取总记录数
        Session session = em.unwrap(Session.class);
        Query countQuery = session.createNativeQuery("select count(*) from (" + sqlStr + ") as t");
        setParameters(countQuery, nativeSql.getParams());


        //获取分页结果
        Query pageQuery = session.createNativeQuery(sqlStr);
        setParameters(pageQuery, nativeSql.getParams());

        long totalRecord = ((Number) countQuery.getSingleResult()).longValue();
        List<X> result = totalRecord == 0 ? new ArrayList<X>(0) :
                pageQuery.setFirstResult(pageNo - 1)
                        .setMaxResults(pageSize)
                        .unwrap(NativeQueryImpl.class)
                        .setResultTransformer(new AliasToBeanTransformer<X>(resultClass))
                        .list();

        return new PageImpl<X>(result, PageRequest.of(pageNo - 1, pageSize), totalRecord);
    }


    /***********************************ql*************************************************/

    /**
     * 根据ql和按照索引顺序的params查询一个实体
     */
    public T findOneByQL(final String ql, final Object... params) {
        List<T> list = findAllByQL(ql, PageRequest.of(0, 1), params);
        if (list.size() > 0) {
            return list.get(0);
        }
        return null;
    }

    public List<T> findAllByQL(final String ql, final Object... params) {
        return findAllByQL(ql, (Pageable) null, params);
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，sort存储排序信息 null表示不排序
     *
     * @param ql
     * @param sort   null表示不排序
     * @param params List<Order> orders = this.find("SELECT o FROM Order o WHERE o.storeId = ? and o.code = ? order by o.createTime desc", storeId, code);
     * @return
     */
    public List<T> findAllByQL(final String ql, final Sort sort, final Object... params) {
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
    public List<T> findAllByQL(final String ql, final Pageable pageable, final Object... params) {
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
    @Override
    public long countByHql(final String ql, final Object... params) {
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


    @SuppressWarnings("unchecked")
    @Override
    public <X> X findObjectByHql(String queryJql, Map<String, ?> params) {

        Query query = this.createQueryWithNameParam(queryJql, params);

        return (X) query.getSingleResult();
    }


    @SuppressWarnings("unchecked")
    @Override
    public <X> X findListByHql(String queryJql, Map<String, ?> params) {

        Query query = this.createQueryWithNameParam(queryJql, params);

        return (X) query.getResultList();
    }


    @SuppressWarnings({"unchecked"})
    @Override
    public List<Map<String, Object>> findMapListByHql(String querySql, Map<String, ?> params) {
        Query query = this.createSqlQueryWithNameParam(querySql, params);
        query.unwrap(NativeQueryImpl.class).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Object> findListBySql(String querySql, Map<String, ?> params) {
        Query query = this.createSqlQueryWithNameParam(querySql, params);
        return query.getResultList();
    }

    /**
     * 根据查询JQL语句与命名参数列表创建Query对象，JQL中参数按名称绑定
     *
     * @param values 参数Map
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Query createQueryWithNameParam(final String queryJql, final Map<String, ?> values) {

        Query query = this.em.createQuery(queryJql);

        if (values != null) {

            Iterator it = values.entrySet().iterator();

            while (it.hasNext()) {

                Map.Entry<String, Object> entry = (Map.Entry<String, Object>) it.next();
                query.setParameter(entry.getKey(), entry.getValue());
            }

        }

        return query;
    }

    /**
     * 根据查询SQL语句与命名参数列表创建Query对象，SQL中参数按名称绑定
     *
     * @param values 参数Map
     */
    @SuppressWarnings({"unchecked"})
    public Query createSqlQueryWithNameParam(final String querySql, final Map<String, ?> values) {

        Query query = this.em.createNativeQuery(querySql);

        if (values != null) {

            for (Map.Entry<String, ?> stringEntry : values.entrySet()) {

                Map.Entry<String, Object> entry = (Map.Entry<String, Object>) stringEntry;
                query.setParameter(entry.getKey(), entry.getValue());
            }

        }

        return query;
    }


    @Override
    public <X> X findObjectBySql(String querySql, Class<X> clazz, final Map<String, ?> params) {
        Query query = this.createSqlQueryWithNameParam(querySql, params);

        Object result = query.getSingleResult();

        return com.sondertara.common.util.BeanUtils.beanToBean(result, clazz);
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public Map<String, Object> findMapBySql(String querySql, Map<String, ?> params) {

        Query query = this.createSqlQueryWithNameParam(querySql, params);

        query.unwrap(NativeQueryImpl.class).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
        return (Map<String, Object>) query.getSingleResult();
    }


    @Override
    public int executeSql(String sql, Map<String, ?> params) {

        Query query = this.createSqlQueryWithNameParam(sql, params);
        return query.executeUpdate();
    }

}
