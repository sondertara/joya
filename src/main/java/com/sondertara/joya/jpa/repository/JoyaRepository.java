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
   * ??????list
   *
   * @param sql ??????sql
   * @param resultClass ????????????
   * @param params ??????
   * @param <T> ??????
   * @return list
   */
  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> List<T> findListBySql(String sql, Class<T> resultClass, Object... params) {
    if (JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, true)) {
      log.info("[findListBySql] SQL:\nJoya-SQL: {}", sql);
    }
    Query query = em.createNativeQuery(sql);
    setParameters(query, params);
    return query
        .unwrap(NativeQuery.class)
        .setResultTransformer(new AliasToBeanTransformer<>(resultClass))
        .list();
  }

  /**
   * find to list
   *
   * @param nativeSql native query
   * @param resultClass result class
   * @param <T> generic
   * @return list
   */
  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> List<T> findListBySql(NativeSqlQuery nativeSql, Class<T> resultClass) {

    if (JoyaSpringContext.getConfig(SQL_VIEW_SWITCH, true)) {
      log.info("[findListBySql] SQL:\nJoya-SQL: {}", nativeSql.toSql());
    }
    Query query = em.createNativeQuery(nativeSql.toSql());
    setParameters(query, nativeSql.getParams());
    return query
        .unwrap(NativeQuery.class)
        .setResultTransformer(new AliasToBeanTransformer<>(resultClass))
        .list();
  }

  /**
   * find map list from table rows
   *
   * @param nativeSql sql
   * @param params params
   * @param camelCase parse key to camelCase
   * @return list
   */
  public List<Map<String, Object>> findMapListBySql(
      NativeSqlQuery nativeSql, List<Object> params, boolean camelCase) {

    return findMapListBySql(nativeSql.toSql(), params, camelCase);
  }

  /**
   * find map from table rows
   *
   * @param nativeSql sql
   * @param params params
   * @param camelCase parse key to camelCase
   * @return list
   */
  public Map<String, Object> findMapBySql(
      NativeSqlQuery nativeSql, List<Object> params, boolean camelCase) {

    return findMapBySql(nativeSql.toSql(), params, camelCase);
  }

  /** (non-Javadoc) */
  @SuppressWarnings({"unchecked", "deprecation"})
  public List<Map<String, Object>> findMapListBySql(
      String querySql, List<Object> params, boolean camelCase) {
    Query query = em.createNativeQuery(querySql);
    setParameters(query, params);
    query
        .unwrap(NativeQuery.class)
        .setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
    return query.getResultList();
  }

  @SuppressWarnings({"unchecked", "deprecation"})
  public Map<String, Object> findMapBySql(String querySql, List<Object> params, boolean camelCase) {
    Query query = em.createNativeQuery(querySql);
    setParameters(query, params);
    query
        .unwrap(NativeQuery.class)
        .setResultTransformer(AliasToMapResultTransformer.getInstance(camelCase));
    return (Map<String, Object>) query.getSingleResult();
  }

  /**
   * query page
   *
   * @param resultClass the result class
   * @param queryParam query params
   * @param targetClass the target query table
   * @param <T> the type of result
   * @return pagination result
   */
  public <T> PageResult<T> queryPage(
      PageQueryParam queryParam, Class<T> resultClass, Class<?>... targetClass) {
    NativeSqlQuery nativeSqlQuery = JoyaPageConvert.buildNativeQuery(queryParam, targetClass);
    return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());
  }

  /**
   * query page
   *
   * @param resultClass the result class
   * @param queryParam query params
   * @param joinPart the join table
   * @param <T> the type of result
   * @return pagination result
   */
  public <T> PageResult<T> queryPage(
      PageQueryParam queryParam, Class<T> resultClass, UnaryOperator<JoinCriterion> joinPart) {

    NativeSqlQuery nativeSqlQuery = JoyaPageConvert.buildNativeQuery(queryParam, joinPart);
    return queryPage(nativeSqlQuery, resultClass, queryParam.getPage(), queryParam.getPageSize());
  }

  /**
   * query page
   *
   * @param sql sql query
   * @param resultClass result class
   * @param pageNo page start
   * @param pageSize page size
   * @param <T> the type of result
   * @param params the query params
   * @return pagination result
   */
  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> PageResult<T> queryPage(
      String sql, Class<T> resultClass, Integer pageNo, Integer pageSize, Object... params) {
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

    List<T> result =
        totalRecord == 0
            ? new ArrayList<>(0)
            : pageQuery
                .setFirstResult(pageNo * pageSize)
                .setMaxResults(pageSize)
                .unwrap(NativeQuery.class)
                .setResultTransformer(new AliasToBeanTransformer<>(resultClass))
                .list();
    return new PageResult<>(pageNo, pageSize, totalRecord, result);
  }

  /**
   * query page
   *
   * @param nativeSql sql query
   * @param resultClass result class
   * @param pageNo page start
   * @param pageSize page size
   * @param <T> the type of result
   * @return pagination result
   */
  @SuppressWarnings({"unchecked", "deprecation"})
  public <T> PageResult<T> queryPage(
      NativeSqlQuery nativeSql, Class<T> resultClass, Integer pageNo, Integer pageSize) {
    return queryPage(
        nativeSql.toSql(),
        resultClass,
        pageNo,
        pageSize,
        null == nativeSql.getParams() ? new Object[0] : nativeSql.getParams().toArray());
  }

  /** ??????ql????????????????????????params?????????????????? */
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
   * ??????ql????????????????????????params??????ql???sort?????????????????? null???????????????
   *
   * @param ql hql sql
   * @param sort null???????????????
   * @param params List<Order> orders = this.find("SELECT o FROM Order o WHERE o.storeId = ? and
   *     o.code = ? order by o.createTime desc", storeId, code);
   * @return list
   */
  @SuppressWarnings("unchecked")
  public <T> List<T> findAllByHql(final String ql, final Sort sort, final Object... params) {
    Query query = em.createQuery(ql + prepareOrder(sort));
    setParameters(query, params);
    return query.getResultList();
  }

  /**
   * ??????ql????????????????????????params??????ql???pageable?????????????????? null???????????????
   *
   * @param ql hql
   * @param pageable null???????????????
   * @param params query
   * @return list
   */
  @SuppressWarnings("unchecked")
  public <T> List<T> findAllByHql(
      final String ql, final Pageable pageable, final Object... params) {
    Query query = em.createQuery(ql + prepareOrder(pageable != null ? pageable.getSort() : null));
    setParameters(query, params);
    if (pageable != null) {
      query.setFirstResult((int) pageable.getOffset());
      query.setMaxResults(pageable.getPageSize());
    }
    return query.getResultList();
  }

  /** ??????ql????????????????????????params??????ql?????? */
  public long countByHql(final String ql, final Object... params) {
    Query query = em.createQuery(ql);
    setParameters(query, params);
    return (Long) query.getSingleResult();
  }

  /** ????????? */
  private String prepareOrder(Sort sort) {
    return (sort == null || !sort.iterator().hasNext())
        ? ""
        : (" order by " + sort.toString().replace(":", " "));
  }

  /** ???????????????Query?????? */
  private void setParameters(Query query, Object[] params) {
    if (params != null && params.length > 0) {
      for (int i = 0; i < params.length; i++) {
        query.setParameter(i + 1, params[i]);
      }
    }
  }

  /** ???????????????Query?????? */
  private void setParameters(Query query, List<Object> params) {
    if (params != null) {
      for (int i = 0; i < params.size(); i++) {
        query.setParameter(i + 1, params.get(i));
      }
    }
  }

  /**
   * Batch update, first execute the update operation, then the insert operation, it is not
   * performed in the transaction by default, and support Spring transaction management
   * ????????????????????????????????????,?????????????????????,???????????????????????????????????????Spring????????????
   *
   * <p>The pojo need extends {@link PersistEntity},and explicitly specifies whether it is new by
   * the field isNew ??????????????????{@link PersistEntity} ??????isNew????????????????????????????????????????????????????????????
   *
   * <p>1.??????mysql????????????????????????????????????{@link PersistEntity#isNew()}?????????????????????null???????????????????????????
   * 2.?????????????????????????????????????????????,?????????dataList???,??????????????????{@link PersistEntity#setNew(boolean)} ?????????????????????
   *
   * @param dataList ??????
   * @param <T> ??????
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
        if (data.isNew() || table.getData().get(table.getPrimaryKey()) == null) {
          if (table.getData().get(table.getPrimaryKey()) == null) {
            table.getData().remove(table.getPrimaryKey());
          }
          insertList.add(table);
        } else {
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
        insertSql =
            StringFormatter.format(
                "insert into {}({}) values({})",
                table.getTableName(),
                String.join(",", data.keySet()),
                String.join(",", set));
      }
      String updateSql = null;
      if (updateList.size() > 0) {
        TableEntity table = updateList.get(0);
        StringJoiner sj = new StringJoiner(", ");
        Map<String, Object> map = table.getData();
        Object primaryKeyValue = map.remove(table.getPrimaryKey());
        for (String key : map.keySet()) {
          sj.add(key + " = ?");
        }
        map.put(table.getPrimaryKey(), primaryKeyValue);
        updateSql =
            StringFormatter.format(
                "update {} set {} where {} = ?",
                table.getTableName(),
                sj.toString(),
                table.getPrimaryKey());
      }
      try (Session session = em.unwrap(Session.class)) {
        String finalUpdateSql = updateSql;
        String finalInsertSql = insertSql;
        log.info(
            "Batch update start,insert size is:{},update size is:{}",
            insertList.size(),
            updateList.size());
        session.doWork(
            connection -> {
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

  private void batchUpdate(String sql, List<TableEntity> rows, Connection connection)
      throws SQLException {
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
