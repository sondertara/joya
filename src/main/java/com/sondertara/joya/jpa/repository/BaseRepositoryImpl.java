package com.sondertara.joya.jpa.repository;


import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.hibernate.transformer.AliasToBeanTransformer;
import com.sondertara.joya.hibernate.transformer.AliasToMapResultTransformer;
import com.sondertara.joya.utils.BeanUtil;
import com.sondertara.joya.utils.SqlUtils;
import org.apache.commons.beanutils.BeanUtils;
import org.hibernate.query.NativeQuery;
import org.hibernate.transform.Transformers;
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
    private final String entityName;
    private final String idName;

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
                BeanUtil.copyPropertiesIgnoreNull(entity, po);
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
        List<T> models = new ArrayList<>();
        for (ID id : ids) {
            T model;
            try {
                model = entityClass.getDeclaredConstructor().newInstance();
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
        deleteAllInBatch(models);

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

    @SuppressWarnings("unchecked")
    private Map<ID, T> toMap(List<T> list) {
        Map<ID, T> result = new LinkedHashMap<>();
        for (T t : list) {
            if (t != null) {
                result.put((ID) eif.getId(t), t);
            }
        }
        return result;
    }


    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public <X> List<X> findListBySql(String sql, Class<X> clazz, Object... params) {
        Query query = em.createNativeQuery(sql);
        setParameters(query, params);
        return query.unwrap(NativeQuery.class).setResultTransformer(new AliasToBeanTransformer<>(clazz)).list();
    }

    @Override
    public <X> List<X> findListBySql(NativeSqlQuery nativeSql, Class<X> resultClass) {
        return findListBySql(nativeSql.toSql(), resultClass, null != nativeSql.getParams() ? nativeSql.getParams().toArray() : new Object[0]);
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public <X> Page<X> queryPageBySql(NativeSqlQuery nativeSql, Class<X> resultClass, Integer pageNo, Integer pageSize) {
        String sqlStr = nativeSql.toSql();

        //获取总记录数
        String countSql = SqlUtils.buildCountSql(sqlStr);
        Query countQuery = em.createNativeQuery(countSql);
        setParameters(countQuery, nativeSql.getParams());

        long totalRecord = ((Number) countQuery.getSingleResult()).longValue();

        //获取分页结果
        Query pageQuery = em.createNativeQuery(sqlStr);
        setParameters(pageQuery, nativeSql.getParams());
        List<X> result = totalRecord == 0 ? new ArrayList<>(0) : pageQuery.setFirstResult(pageNo).setMaxResults(pageSize).unwrap(NativeQuery.class).setResultTransformer(new AliasToBeanTransformer<>(resultClass)).list();

        return new PageImpl<>(result, PageRequest.of(pageNo, pageSize), totalRecord);
    }


    /**
     * 根据ql和按照索引顺序的params查询一个实体
     */
    public T findOneByHql(final String ql, final Object... params) {
        List<T> list = findAllByHql(ql, PageRequest.of(0, 1), params);
        if (list.size() > 0) {
            return list.get(0);
        }
        return null;
    }

    public List<T> findAllByHql(final String ql, final Object... params) {
        return findAllByHql(ql, null, params);
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，sort存储排序信息 null表示不排序
     *
     * @param ql     hql sql
     * @param sort   null表示不排序
     * @param params List<Order> orders = this.find("SELECT o FROM Order o WHERE o.storeId = ? and o.code = ? order by o.createTime desc", storeId, code);
     * @return list
     */
    @SuppressWarnings("unchecked")
    public List<T> findAllByHqL(final String ql, final Sort sort, final Object... params) {
        Query query = em.createQuery(ql + prepareOrder(sort));
        setParameters(query, params);
        return query.getResultList();
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，pageable存储分页信息 null表示不分页
     *
     * @param ql       hql
     * @param pageable null表示不分页
     * @param params   param
     * @return list
     */
    @SuppressWarnings("unchecked")
    public List<T> findAllByHql(final String ql, final Pageable pageable, final Object... params) {
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
        return (sort == null || !sort.iterator().hasNext()) ? "" : (" order by " + sort.toString().replace(":", " "));
    }

    /**
     * 按顺序设置Query参数
     */
    private void setParameters(Query query, Object[] params) {
        if (params != null && params.length > 0) {
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
        }
    }

    /**
     * 按顺序设置Query参数
     */
    private void setParameters(Query query, List<Object> params) {
        if (params != null && params.size() > 0) {
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


    @Override
    public List<Map<String, Object>> findMapListBySql(NativeSqlQuery nativeSql, boolean camelCase) {
        return findMapListBySql(nativeSql.toSql(), nativeSql.getParams(), camelCase);
    }

    @Override
    public Map<String, Object> findMapBySql(NativeSqlQuery nativeSql, boolean camelCase) {
        return findMapBySql(nativeSql.toSql(), nativeSql.getParams(), camelCase);
    }

    /**
     * (non-Javadoc)
     */
    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public List<Map<String, Object>> findMapListBySql(String querySql, List<Object> params, boolean camelCase) {
        Query query = em.createNativeQuery(querySql);
        setParameters(query, params);
        query.unwrap(NativeQuery.class).setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
        return query.getResultList();
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public Map<String, Object> findMapBySql(String querySql, List<Object> params, boolean camelCase) {
        Query query = em.createNativeQuery(querySql);
        setParameters(query, params);
        query.unwrap(NativeQuery.class).setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
        return (Map<String, Object>) query.getSingleResult();
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Override
    public List<Map<String, Object>> findMapListByHql(String querySql, Map<String, ?> params) {
        Query query = this.createSqlQueryWithNameParam(querySql, params);
        query.unwrap(NativeQuery.class).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
        return query.getResultList();
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public List<Object> findListBySql(String querySql, Map<String, ?> params) {
        Query query = this.createSqlQueryWithNameParam(querySql, params);
        return query.getResultList();
    }

    /**
     * 根据查询JQL语句与命名参数列表创建Query对象，JQL中参数按名称绑定
     *
     * @param values 参数Map
     */
    @SuppressWarnings({"unchecked"})
    public Query createQueryWithNameParam(final String queryJql, final Map<String, ?> values) {

        Query query = this.em.createQuery(queryJql);

        if (values != null) {

            for (Map.Entry<String, ?> stringEntry : values.entrySet()) {

                Map.Entry<String, Object> entry = (Map.Entry<String, Object>) stringEntry;
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

    @SuppressWarnings({"unchecked", "deprecation"})
    @Override
    public Map<String, Object> findMapBySql(String querySql, Map<String, ?> params) {

        Query query = this.createSqlQueryWithNameParam(querySql, params);

        query.unwrap(NativeQuery.class).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
        return (Map<String, Object>) query.getSingleResult();
    }


    @Override
    public int executeSql(String sql, Map<String, ?> params) {

        Query query = this.createSqlQueryWithNameParam(sql, params);
        return query.executeUpdate();
    }

}
