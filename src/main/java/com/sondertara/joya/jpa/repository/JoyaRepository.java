package com.sondertara.joya.jpa.repository;


import com.sondertara.common.util.CollectionUtils;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.joya.cache.TableClassCache;
import com.sondertara.joya.core.model.TableEntity;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.pagination.JoyaPageConvert;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.PageResult;
import com.sondertara.joya.domain.PersistEntity;
import com.sondertara.joya.ext.JoyaSpringContext;
import com.sondertara.joya.hibernate.transformer.AliasToBeanTransformer;
import com.sondertara.joya.hibernate.transformer.AliasToMapResultTransformer;
import com.sondertara.joya.jpa.repository.statment.SimpleBatchPreparedStatementSetter;
import com.sondertara.joya.utils.SqlUtils;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * dao
 *
 * @author huangxiaohu
 */
@SuppressWarnings("deprecated")
@Transactional(readOnly = true)
public class JoyaRepository {

    private static final Logger log = LoggerFactory.getLogger(JoyaRepository.class);
    private static final String SQL_VIEW_SWITCH = "joya.sql-view";

    private static final int BATCH_SIZE = 500;

    private final EntityManager em;

    public JoyaRepository(EntityManager em) {
        this.em = em;
    }


    /**
     * 查询list
     *
     * @param sql         原生sql
     * @param resultClass 目标实体
     * @param params      参数
     * @param <T>         泛型
     * @return list
     */
    @SuppressWarnings({"unchecked"})
    public <T> List<T> findListBySql(String sql, Class<T> resultClass, Object... params) {
        if (JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, true)) {
            log.info("[findListBySql] SQL:\nJoya-SQL: {}", sql);
        }
        Query query = em.createNativeQuery(sql);
        setParameters(query, params);
        return query.unwrap(NativeQuery.class).setResultTransformer(new AliasToBeanTransformer<>(resultClass)).list();
    }

    /**
     * find to list
     *
     * @param nativeSql   native query
     * @param resultClass result class
     * @param <T>         generic
     * @return list
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findListBySql(NativeSqlQuery nativeSql, Class<T> resultClass) {

        if (JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, true)) {
            log.info("[findListBySql] SQL:\nJoya-SQL: {}", nativeSql.toSql());
        }
        Query query = em.createNativeQuery(nativeSql.toSql());
        setParameters(query, nativeSql.getParams());
        return query.unwrap(NativeQuery.class).setResultTransformer(new AliasToBeanTransformer<>(resultClass)).list();
    }

    /**
     * find map list from table rows
     *
     * @param nativeSql sql
     * @param params    params
     * @param camelCase parse key to camelCase
     * @return list
     */
    public List<Map<String, Object>> findMapListBySql(NativeSqlQuery nativeSql, List<Object> params, boolean camelCase) {

        return findMapListBySql(nativeSql.toSql(), params, camelCase);
    }

    /**
     * find map from table rows
     *
     * @param nativeSql sql
     * @param params    params
     * @param camelCase parse key to camelCase
     * @return list
     */
    public Map<String, Object> findMapBySql(NativeSqlQuery nativeSql, List<Object> params, boolean camelCase) {

        return findMapBySql(nativeSql.toSql(), params, camelCase);
    }

    /**
     * (non-Javadoc)
     */
    @SuppressWarnings({"unchecked"})
    public List<Map<String, Object>> findMapListBySql(String querySql, List<Object> params, boolean camelCase) {
        Query query = em.createNativeQuery(querySql);
        setParameters(query, params);
        query.unwrap(NativeQuery.class).setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
        return query.getResultList();
    }

    @SuppressWarnings({"unchecked"})
    public Map<String, Object> findMapBySql(String querySql, List<Object> params, boolean camelCase) {
        Query query = em.createNativeQuery(querySql);
        setParameters(query, params);
        query.unwrap(NativeQuery.class).setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
        return (Map<String, Object>) query.getSingleResult();
    }

    /**
     * query page
     *
     * @param resultClass the result class
     * @param queryParam  query params
     * @param targetClass the target query table
     * @param <T>         the type of result
     * @return pagination result
     */
    public <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, Class<?>... targetClass) {
        NativeSqlQuery nativeSqlQuery = JoyaPageConvert.buildNativeQuery(queryParam, targetClass);
        return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());

    }

    /**
     * query page
     *
     * @param resultClass the result class
     * @param queryParam  query params
     * @param joinPart    the join  table
     * @param <T>         the type of result
     * @return pagination result
     */
    public <T> PageResult<T> queryPage(PageQueryParam queryParam, Class<T> resultClass, UnaryOperator<JoinCriterion> joinPart) {

        NativeSqlQuery nativeSqlQuery = JoyaPageConvert.buildNativeQuery(queryParam, joinPart);
        return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());

    }


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
    @SuppressWarnings("unchecked")
    public <T> PageResult<T> queryPage(String sql, Class<T> resultClass, Integer pageNo, Integer pageSize, Object... params) {
        Boolean opened = JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, false);
        if (opened) {
            log.info("[queryPage] SQL:\nJoya-SQL: {}", sql);
        }
        // get the count sql
        String countSql = SqlUtils.buildCountSql(sql);
        Query countQuery = em.createNativeQuery(countSql);
        setParameters(countQuery, params);
        long totalRecord = ((Number) countQuery.getSingleResult()).longValue();

        // the query
        Query pageQuery = em.createNativeQuery(sql);

        setParameters(pageQuery, params);


        List<T> result = totalRecord == 0 ? new ArrayList<>(0) : pageQuery.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).unwrap(NativeQuery.class).setResultTransformer(new AliasToBeanTransformer<>(resultClass)).list();

        return new PageResult<>(pageNo, pageSize, totalRecord, result);
    }

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
    @SuppressWarnings("unchecked")
    public <T> PageResult<T> queryPage(NativeSqlQuery nativeSql, Class<T> resultClass, Integer pageNo, Integer pageSize) {
        return queryPage(nativeSql.toSql(), resultClass, pageNo, pageSize, null == nativeSql.getParams() ? new Object[0] : nativeSql.getParams().toArray());
    }


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
     * @param ql     hql sql
     * @param sort   null表示不排序
     * @param params List<Order> orders = this.find("SELECT o FROM Order o WHERE o.storeId = ? and o.code = ? order by o.createTime desc", storeId, code);
     * @return list
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> findAllByHql(final String ql, final Sort sort, final Object... params) {
        Query query = em.createQuery(ql + prepareOrder(sort));
        setParameters(query, params);
        return query.getResultList();
    }

    /**
     * 根据ql和按照索引顺序的params执行ql，pageable存储分页信息 null表示不分页
     *
     * @param ql       hql
     * @param pageable null表示不分页
     * @param params   query
     * @return list
     */
    @SuppressWarnings("unchecked")
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
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                query.setParameter(i + 1, params.get(i));
            }
        }
    }

    /**
     * Batch update, first execute the update operation, then the insert operation,
     * it is not performed in the transaction by default, and support Spring transaction management
     * 批量更新，先执行更新操作,后执行插入操作,默认不是在事务中执行，支持Spring事务管理
     * <p>
     * The pojo need extends {@link PersistEntity},and explicitly specifies whether it is new by the field isNew
     * 实体需要继承{@link PersistEntity} 通过isNew字段来表示是否是新建还是更新，默认是新增
     * <p>
     * 1.对于mysql主键自增这种实体通过重写{@link PersistEntity#isNew()}判断主键是否为null就可以标识是否新增
     * 2.对于通过序列来设置主键值的情形,在构造dataList时,需要显示调用{@link  PersistEntity#setNew(boolean)} 来标识是否新增
     *
     * @param dataList 数据
     * @param <T>      类型
     */
    @Transactional(readOnly = false, propagation = Propagation.SUPPORTS)
    public <T extends PersistEntity> void batchUpdate(List<T> dataList) throws SQLException {
        try {
            if (CollectionUtils.isEmpty(dataList)) {
                return;
            }
            List<TableEntity> insertList = new ArrayList<>();
            List<TableEntity> updateList = new ArrayList<>();
            for (T data : dataList) {
                TableEntity table = TableClassCache.getInstance().getTable(data, true);
                if (data.isNew()) {
                    // auto increment
                    if (table.getData().get(table.getPrimaryKey()) == null) {
                        table.getData().remove(table.getPrimaryKey());
                    }
                    insertList.add(table);
                } else {
                    Object remove = table.getData().remove(table.getPrimaryKey());
                    table.getData().put(table.getPrimaryKey(), remove);
                    updateList.add(table);
                }
            }
            String insertSql = null;
            if (insertList.size() > 0) {
                TableEntity table = insertList.get(0);
                Map<String, Object> data = table.getData();
                // auto increment
                if (null == data.get(table.getPrimaryKey())) {
                    data.remove(table.getPrimaryKey());
                }
                List<String> set = data.keySet().stream().map(s -> "?").collect(Collectors.toList());
                insertSql = StringFormatter.format("insert into {}({}) values({})", table.getTableName(), String.join(",", data.keySet()), String.join(",", set));
            }
            String updateSql = null;
            if (updateList.size() > 0) {
                TableEntity table = updateList.get(0);
                StringJoiner sj = new StringJoiner(", ");
                Map<String, Object> map = table.getData();
                for (String key : map.keySet()) {
                    if (key.equals(table.getPrimaryKey())) {
                        continue;
                    }
                    sj.add(key + " = ?");
                }
                updateSql = StringFormatter.format("update {} set {} where {} = ?", table.getTableName(), sj.toString(), table.getPrimaryKey());
            }
            try (Session session = em.unwrap(Session.class)) {
                String finalUpdateSql = updateSql;
                String finalInsertSql = insertSql;
                log.info("Batch update start,insert size is:{},update size is:{}", insertList.size(), updateList.size());
                if (JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, false)) {
                    log.info("Batch update sql:{}", finalUpdateSql);
                    log.debug("Batch insert sql:{}", finalInsertSql);
                }
                session.doWork(connection -> {
                    if (null != finalUpdateSql) {
                        batchUpdate(finalUpdateSql, updateList, connection);
                    }
                    if (null != finalInsertSql) {
                        batchUpdate(finalInsertSql, insertList, connection);
                    }
                });
            }
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e.getCause());
        }

        log.info("Batch update completed.");
    }

    private void batchUpdate(String sql, List<TableEntity> rows, Connection connection) throws SQLException {
        if (null == sql) {
            return;
        }
        SimpleBatchPreparedStatementSetter setter = new SimpleBatchPreparedStatementSetter(rows);
        PreparedStatement ps = connection.prepareStatement(sql);
        int batchSize = setter.getBatchSize();
        if (JdbcUtils.supportsBatchUpdates(ps.getConnection())) {
            for (int j = 0; j < batchSize; j++) {
                setter.setValues(ps, j);
                ps.addBatch();
                if ((j + 1) % BATCH_SIZE == 0 || j == batchSize - 1) {
                    ps.executeBatch();
                    ps.clearBatch();
                }
            }
        } else {
            for (int i = 0; i < batchSize; i++) {
                setter.setValues(ps, i);
                ps.executeUpdate();
            }
        }
        if (!ps.isClosed()) {
            ps.close();
        }
    }
}
