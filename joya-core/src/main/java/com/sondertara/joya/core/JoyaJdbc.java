package com.sondertara.joya.core;

import com.sondertara.common.base.Assert;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.Lists;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.collection.Sets;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.model.PageResult;
import com.sondertara.common.text.StringUtils;
import com.sondertara.common.timing.Stopwatch;
import com.sondertara.joya.core.constant.JoyaConfig;
import com.sondertara.joya.core.jdbc.JdbcOperations;
import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.callback.PreparedStatementCallback;
import com.sondertara.joya.core.jdbc.callback.TransactionCallback;
import com.sondertara.joya.core.jdbc.executor.JdbcExecutor;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ListRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleRowRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.StreamRecordExtractor;
import com.sondertara.joya.core.jdbc.statment.BatchPreparedStatementSetter;
import com.sondertara.joya.core.jdbc.statment.PreparedStatementCreator;
import com.sondertara.joya.core.jdbc.statment.SqlProvider;
import com.sondertara.joya.core.jdbc.statment.creator.DeleteSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.InsertSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.SelectSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.UpdateBatchByIdSqlCreator;
import com.sondertara.joya.core.jdbc.statment.creator.UpdateSqlCreator;
import com.sondertara.joya.core.jdbc.support.ConnectionContext;
import com.sondertara.joya.core.jdbc.support.TransactionSynchronization;
import com.sondertara.joya.core.jdbc.transaction.LocalTransactionManager;
import com.sondertara.joya.core.jdbc.transaction.TransactionManager;
import com.sondertara.joya.core.jdbc.support.ScriptRunner;
import com.sondertara.joya.core.jdbc.support.SqlParameterValue;
import com.sondertara.joya.core.jdbc.support.TableMetaContext;
import com.sondertara.joya.core.jdbc.support.dialect.BoundSql;
import com.sondertara.joya.core.jdbc.support.dialect.PageSqlDialect;
import com.sondertara.joya.core.jdbc.support.dialect.SqlDialectRegister;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.jdbc.transaction.TransactionTemplate;
import com.sondertara.joya.core.jdbc.transaction.TransactionTemplateImpl;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.WhereCondition;
import com.sondertara.joya.core.query.pagination.JoyaPageConvert;
import com.sondertara.joya.core.query.pagination.PageParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.JdbcUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JDBC工具类
 *
 * @author huangxiaohu
 */
public class JoyaJdbc extends JdbcAccessor implements JdbcOperations, JoyaOperations {

    private static final Logger logger = LoggerFactory.getLogger(JoyaJdbc.class);
    private final TransactionManager transactionManager;
    private final JdbcExecutor jdbcExecutor;

    /**
     * 获取全局配置（过渡桥，阶段0引入，阶段6退役）。
     * 供 {@link com.sondertara.joya.core.model.EntityTableMetaDefinition}、{@link com.sondertara.joya.core.jdbc.statment.creator.InsertSqlCreator}
     * 等通过 {@code JoyaJdbc.config()} 获取 JVM 级配置。
     *
     * @return 全局配置实例
     */
    public static JoyaConfig config() {
        return JoyaConfig.instance();
    }

    /**
     * 创建JoyaJdbc
     *
     * @param dataSource 数据源
     */
    public JoyaJdbc(DataSource dataSource) {
        this.dataSource = dataSource;
        this.transactionManager = new LocalTransactionManager(dataSource);
        this.jdbcExecutor = new JdbcExecutor(dataSource);
    }
    /**
     * 自定义DataSource 销毁
     *
     * @param consumer func
     */
    public void destroy(Consumer<DataSource> consumer) {
        consumer.accept(this.dataSource);
    }


    /**
     * 自定义DataSource 销毁
     */
    public void destroy() {
        config().cacheClean();
    }

    /**
     * 判断当前是否在事务中
     */
    public boolean inTransaction() {
        ConnectionContext ctx = TransactionSynchronization.getCurrent(dataSource);
        return ctx != null && ctx.isOpen() && ctx.hasTransaction();
    }

    /**
     * 查询数据库并转换结果集。
     * 用户可自定义结果集转换器。
     * 用户也可使用预定义的结果集转换器。
     *
     * @param sql          sql语句
     * @param recordMapper 结果集转换器
     * @param params       sql参数
     * @param <T>          resultSetMapper返回的结果类型
     * @return 成功则返回转换结果，失败则抛出DbException，结果为空则返回空列表
     * @see ResultSetExtractor
     * @see ListRecordExtractor
     * @see SingleRowRecordExtractor
     */
    @Override
    public <T> T query(String sql, ResultSetExtractor<T> recordMapper, Object... params) {
        return jdbcExecutor.query(sql, recordMapper, params);
    }

    /**
     * query stream
     *
     * @param sql       the sql
     * @param rowMapper row mapper
     * @param params    the query params
     * @param <T>       the return type
     * @return the stream
     */
    @Override
    public <T> Stream<T> queryStream(String sql, RowMapper<T> rowMapper, Object... params) {
        return jdbcExecutor.queryStream(sql, rowMapper, params);
    }

    /**
     * 查询数据库，对结果集的每一行进行转换，然后将所有行封装成列表。
     * 用户可自定义行转换器。
     * 用户也可使用预定义的行转换器。
     *
     * @param sql       sql语句
     * @param rowMapper 行转换器
     * @param params    sql参数
     * @param <T>       rowMapper返回的结果类型
     * @return 成功则返回结果列表，失败则抛出DbException，结果为空则返回空列表
     * @see BeanRowMapper
     * @see MapRowMapper
     * @see SingleColumnRowMapper
     */
    @Override
    public <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... params) {
        return jdbcExecutor.queryList(sql, rowMapper, params);
    }

    /**
     * 查询数据库，将结果集的每一行转换成JavaBean，然后将所有行封装成列表。
     *
     * @param sql    sql语句
     * @param type   JavaBean类型
     * @param params sql参数
     * @param <T>    JavaBean类型
     * @return 成功则返回结果列表，失败则抛出DbException，结果为空则返回空列表
     */
    @Override
    public <T> List<T> queryList(String sql, Class<T> type, Object... params) {
        return jdbcExecutor.queryList(sql, type, params);
    }


    /**
     * 查询数据库，返回结果集中的单个值。
     * 如果结果集中有多个值，则只返回第一行第一列的值。
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回结果值，失败则抛出DbException，结果为空则返回null
     */
    @Override
    public <T> T querySingleValue(String sql, Class<T> clazz, Object... params) {
        return jdbcExecutor.querySingleValue(sql, clazz, params);
    }


    /**
     * 查询数据库，返回结果集中的单行数据。
     * 如果结果集中有多行数据，则只返回第一行数据。
     * 用户可自定义行转换器。
     * 用户也可使用预定义的行转换器。
     *
     * @param sql       sql语句
     * @param rowMapper 行转换器
     * @param params    sql参数
     * @param <T>       rowMapper返回的结果类型
     * @return 成功则返回结果，失败则抛出DbException，结果为空则返回null
     * @see RowMapper
     * @see BeanRowMapper
     * @see MapRowMapper
     * @see SingleColumnRowMapper
     */
    @Override
    public <T> T querySingleRow(String sql, RowMapper<T> rowMapper, Object... params) {
        return jdbcExecutor.querySingleRow(sql, rowMapper, params);
    }

    /**
     * 查询数据库，将结果集中的单行数据转换成JavaBean。
     *
     * @param sql    sql语句
     * @param type   JavaBean类型
     * @param params sql参数
     * @param <T>    JavaBean类型
     * @return 成功则返回结果，失败则抛出DbException，结果为空则返回null
     */
    @Override
    public <T> T querySingleRow(String sql, Class<T> type, Object... params) {
        return jdbcExecutor.querySingleRow(sql, type, params);
    }

    /**
     * 更新数据库，返回影响行数
     *
     * @param sql sql语句
     * @return 成功则返回影响行数，失败则抛出DbException
     */
    @Override
    public int execute(String sql) {
        return jdbcExecutor.execute(sql);
    }

    /**
     * 更新数据库，返回影响行数
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回影响行数，失败则抛出DbException
     */
    @Override
    public int execute(String sql, Object[] params) {
        return jdbcExecutor.execute(sql, params);
    }


    @Override
    public Object insert(String sql, Object[] params) {
        return jdbcExecutor.insert(sql, params);
    }


    @Override
    public <T> void insertAll(List<T> dataList) {
        if (CollectionUtils.isEmpty(dataList)) {
            return;
        }
        List<TableMetaContext> insertRowsWithoutKey = new ArrayList<>();
        List<TableMetaContext> insertRowsWithKey = new ArrayList<>();


        for (T data : dataList) {
            TableMetaContext context = TableMetaContext.of(data);
            Object primaryKet = context.getPrimaryKeyValue();
            // auto increment
            if (primaryKet == null) {
                insertRowsWithoutKey.add(context);
            } else {
                insertRowsWithKey.add(context);
            }
        }
        String insertSqlWithoutKey;

        if (insertRowsWithoutKey.size() > 0) {
            insertSqlWithoutKey = buildInsertWithoutKey(insertRowsWithoutKey);

        } else {
            insertSqlWithoutKey = null;
        }
        String insertSqlWithKey;
        if (insertRowsWithKey.size() > 0) {
            insertSqlWithKey = buildInsertWithKey(insertRowsWithKey);
        } else {
            insertSqlWithKey = null;
        }
        logger.info("Batch insert start,insert-without-key size is:{},insert-with-key size is:{}", insertRowsWithoutKey.size(), insertRowsWithKey.size());
        if (logger.isDebugEnabled()) {
            logger.debug("Batch insert-without-key sql:{}", insertSqlWithoutKey);
            logger.debug("Batch insert-with-key sql:{}", insertSqlWithKey);
        }
        doWork(conn -> {
            batchUpdateInternal(insertSqlWithoutKey, insertRowsWithoutKey, conn, false);
            batchUpdateInternal(insertSqlWithKey, insertRowsWithKey, conn, false);
            return null;
        });

        logger.info("Batch update completed.");

    }

    @Override
    public <T> int updateEntityByCondition(T entity, UnaryOperator<WhereCondition<T>> func) {
        TableMetaContext context = TableMetaContext.of(entity);
        UpdateSqlCreator<T> updateSqlCreator = new UpdateSqlCreator<>(context, true);
        updateSqlCreator.where(func);
        return execute(updateSqlCreator.create(), updateSqlCreator.createParameters());
    }

    @Override
    public <T> void updateEntityIgnoreNull(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        context.propertyNotNull();
        context.initData();
        UpdateBatchByIdSqlCreator creator = new UpdateBatchByIdSqlCreator(context, true);
        execute(creator.create(), creator.createParameters());
    }

    @Override
    public <T> void updateEntity(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        context.initData();
        UpdateBatchByIdSqlCreator creator = new UpdateBatchByIdSqlCreator(context);
        execute(creator.create(), creator.createParameters());

    }

    public <T> T execute(String sql, PreparedStatementCallback<T> action) throws DbException {
        return jdbcExecutor.execute(sql, action);
    }

    public <T> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action, boolean closeResources) throws DbException {
        return jdbcExecutor.execute(psc, action, closeResources);
    }


    @Override
    public int[] batchUpdate(String sql, final BatchPreparedStatementSetter pss) throws DbException {
        return jdbcExecutor.batchUpdate(sql, pss);
    }

    @Override
    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        return jdbcExecutor.batchUpdate(sql, batchArgs);
    }

    @Override
    public int[] batchUpdate(String sql, List<Object[]> batchArgs, int[] argTypes) {
        return jdbcExecutor.batchUpdate(sql, batchArgs, argTypes);
    }

    @Override
    public <T> void updateAll(List<T> dataList) {
        if (CollectionUtils.isEmpty(dataList)) {
            return;
        }
        final List<TableMetaContext> updateList = new ArrayList<>();

        for (T data : dataList) {
            TableMetaContext context = TableMetaContext.of(data);
            updateList.add(context);

        }
        TableMetaContext context = updateList.get(0);
        UpdateBatchByIdSqlCreator creator = new UpdateBatchByIdSqlCreator(context);
        String updateSql = creator.create();
        updateList.forEach(metaContext -> {
            metaContext.handleEntityListener(listener -> listener.preUpdate(metaContext.getEntity()));
            metaContext.setPreparedParameterNames(creator.createParameterNames());
        });


        logger.info("Batch update start,update size is:{}", updateList.size());
        if (logger.isDebugEnabled()) {
            logger.debug("Batch update sql:{}", updateSql);
        }
        doWork(conn -> {
            batchUpdateInternal(updateSql, updateList, conn, true);
            return null;
        });
        logger.info("Batch update completed.");
    }


    /**
     * 编程式事务模板,使用方式:
     * <pre>
     *   joyaJdbc.transactionTemplate().execute(status -> {
     *       // 事务内代码
     *       return result;
     *   });
     * </pre>
     */
    public TransactionTemplate transactionTemplate() {
        return new TransactionTemplateImpl(transactionManager);
    }

    /**
     * 暴露底层 {@link TransactionManager}, 供声明式事务拦截器或高级场景使用。
     */
    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    /**
     * run transaction with connection
     *
     * @param action callback
     * @param <T>    callback type
     * @return result
     */
    public <T> T doInTransactionCallback(TransactionCallback<T> action) {
        try {
            return new TransactionTemplateImpl(transactionManager).execute(action);
        } catch (Throwable e) {
            throw new DbException(e);
        }
    }

    private Connection obtainConnection() {
        return TransactionSynchronization.getOrCreate(this.dataSource).getConnection();
    }

    public <T> T doWork(ConnectionCallback<T> action) {
        return jdbcExecutor.doWork(action);
    }

    /**
     * run transaction with connection.
     *
     * @param action statement callback
     * @param <T>    type of result
     * @return result
     */
    public <T> T doInTransaction(ConnectionCallback<T> action) {

        try {
            return doInTransactionCallback(tranStatus -> {
                Connection connection = null;
                try {
                    connection = obtainConnection();
                    return action.doWork(connection);
                } finally {
                    TransactionSynchronization.release(dataSource);
                }
            });
        } catch (Exception e) {
            logger.error("TransactionCallback", e);
            throw new TaraException(e);
        }
    }

    @Override
    public <T> T find(Class<T> tClass, UnaryOperator<WhereCondition<T>> func) {
        TableMetaContext context = TableMetaContext.of(tClass);
        SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
        if (null != func) {
            creator.where(func);
        }
        return querySingleRow(creator.create(), tClass, creator.createParameters());
    }

    @Override
    public <T> List<T> queryTop(String sql, int top, RowMapper<T> rowMapper, Object... params) {
        return jdbcExecutor.doWork(conn -> {
            PageSqlDialect dialect = SqlDialectRegister.getDialect(conn);
            BoundSql pageSql = dialect.pageSql(new BoundSql.BoundSqlObj(sql, params), 0, top);
            return jdbcExecutor.queryList(pageSql.getSqlString(), rowMapper, pageSql.getArgs());
        });
    }

    @Override
    public <T> List<T> queryTop(String sql, int top, Class<T> tClass, Object... params) {
        return jdbcExecutor.doWork(conn -> {
            PageSqlDialect dialect = SqlDialectRegister.getDialect(conn);
            BoundSql pageSql = dialect.pageSql(new BoundSql.BoundSqlObj(sql, params), 0, top);
            return jdbcExecutor.queryList(pageSql.getSqlString(), tClass, pageSql.getArgs());
        });
    }

    @Override
    public <T> List<T> findTop(Class<T> tClass, int topNum, UnaryOperator<WhereCondition<T>> func) {
        return jdbcExecutor.doWork(conn -> {
            PageSqlDialect dialect = SqlDialectRegister.getDialect(conn);
            TableMetaContext context = TableMetaContext.of(tClass);
            SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
            if (null != func) {
                creator.where(func);
            }
            BoundSql pageSql = dialect.pageSql(new BoundSql.BoundSqlObj(creator.create(), creator.createParameters()), 0, topNum);
            return jdbcExecutor.queryList(pageSql.getSqlString(), tClass, pageSql.getArgs());
        });
    }

    @Override
    public <T> List<T> findList(Class<T> tClass, UnaryOperator<WhereCondition<T>> func) {
        TableMetaContext context = TableMetaContext.of(tClass);
        SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
        if (null != func) {
            creator.where(func);
        }
        return queryList(creator.create(), tClass, creator.createParameters());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, R> List<R> findIdExists(Class<T> t, Collection<R> keys) {
        TableMetaContext context = TableMetaContext.of(t);
        SelectSqlCreator<Object> creator = new SelectSqlCreator<>(context);
        creator.setDeclaredColumns(Sets.newHashSet(context.getPrimaryKeyName()));
        creator.where(w -> w.in(context.getPrimaryKeyName(), keys));
        return (List<R>) queryList(creator.create(), context.getPrimaryKeyType(), creator.createParameters());
    }

    @Override
    public <T> List<T> findAllById(Class<T> t, Collection<?> keys) {
        TableMetaContext context = TableMetaContext.of(t);
        SelectSqlCreator<Object> creator = new SelectSqlCreator<>(context);
        creator.where(w -> w.in(context.getPrimaryKeyName(), keys));
        return queryList(creator.create(), t, creator.createParameters());
    }

    @Override
    public <T> T findById(Class<T> t, Serializable key) {
        TableMetaContext context = TableMetaContext.of(t);
        SelectSqlCreator<Object> creator = new SelectSqlCreator<>(context);
        creator.where(w -> w.eq(context.getPrimaryKeyName(), key));
        return querySingleRow(creator.create(), t, creator.createParameters());
    }


    @Override
    public <T> PageResult<T> queryPage(String sqlStr, RowMapper<T> rowMapper, PageParam page, Object... params) {
        return doWork(conn -> {
            PageSqlDialect dialect = SqlDialectRegister.getDialect(conn);
            String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(sqlStr);
            BoundSql.BoundSqlObj boundSqlObj = new BoundSql.BoundSqlObj(jdbcStyleSql, params);
            BoundSql countSql = dialect.countSql(boundSqlObj);
            Long count = querySingleValue(countSql.getSqlString(), Long.class, countSql.getArgs());
            BoundSql pageSql = dialect.pageSql(boundSqlObj, page.startPosition(), page.getPageSize());
            List<T> list = queryList(pageSql.getSqlString(), rowMapper, pageSql.getArgs());
            return new PageResult<>(list, count, page.getPage(), page.getPageSize());
        });
    }

    @Override
    public <T> PageResult<T> queryPage(NativeSqlQuery nativeSqlQuery, RowMapper<T> rowMapper, PageParam page) {
        return queryPage(nativeSqlQuery.toSql(), rowMapper, page, nativeSqlQuery.getParams().toArray());
    }

    @Override
    public <T> PageResult<T> queryPage(NativeSqlQuery nativeSqlQuery, Class<T> tClass, PageParam page) {
        return queryPage(nativeSqlQuery, new BeanRowMapper<>(tClass), page);
    }

    @Override
    public <T> PageResult<T> queryPage(PageQueryParam queryParam, RowMapper<T> rowMapper) {
        NativeSqlQuery query = JoyaPageConvert.create()
                .queryParam(queryParam)
                .build();
        return queryPage(query, rowMapper, PageParam.of(queryParam.getPage(), queryParam.getPageSize()));
    }

    @Override
    public <T> PageResult<T> queryPage(String sql, Class<T> tClass, PageParam page, Object... params) {
        return queryPage(sql, new BeanRowMapper<>(tClass), page, params);
    }


    @Override
    public <T> T find(NativeSqlQuery nativeSqlQuery, Class<T> tClass) {
        return querySingleRow(nativeSqlQuery.toSql(), tClass, nativeSqlQuery.getParams().toArray());
    }

    @Override
    public <T> List<T> findList(NativeSqlQuery nativeSqlQuery, Class<T> tClass) {
        return queryList(nativeSqlQuery.toSql(), tClass, nativeSqlQuery.getParams().toArray());
    }


    @Override
    public long count(NativeSqlQuery nativeSqlQuery) {
        String countSql = SqlFormatUtils.buildCountSql(nativeSqlQuery.toSql());
        return querySingleValue(countSql, Long.class, nativeSqlQuery.getParams().toArray());
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T> T query(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
        WhereCondition<T> condition = new WhereCondition<>();
        for (Map.Entry<String, Object> entry : context.getData().entrySet()) {
            if (entry.getValue() != null) {
                condition.eq(entry.getKey(), entry.getValue());
            }
        }
        creator.setWhereCriterion(condition);
        return (T) querySingleRow(creator.create(), entity.getClass(), creator.createParameters());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> findList(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        SelectSqlCreator<T> creator = new SelectSqlCreator<>(context);
        WhereCondition<T> condition = new WhereCondition<>();
        for (Map.Entry<String, Object> entry : context.getData().entrySet()) {
            if (entry.getValue() != null) {
                condition.eq(entry.getKey(), entry.getValue());
            }
        }
        creator.setWhereCriterion(condition);
        return (List<T>) queryList(creator.create(), entity.getClass(), creator.createParameters());
    }

    @Override
    public <T> int deleteById(Class<T> t, Serializable key) {
        TableMetaContext context = TableMetaContext.of(t);
        DeleteSqlCreator<T> creator = new DeleteSqlCreator<>(context);
        creator.where(w -> w.eq(context.getPrimaryKeyName(), key));
        return execute(creator.create(), creator.createParameters());
    }

    @Override
    public <T> int deleteByCondition(Class<T> t, UnaryOperator<WhereCondition<T>> func) {
        TableMetaContext context = TableMetaContext.of(t);
        DeleteSqlCreator<T> creator = new DeleteSqlCreator<>(context);
        creator.where(func);
        return execute(creator.create(), creator.createParameters());
    }

    @Override
    public <T> void deleteEntity(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        WhereCondition<T> criterion1 = new WhereCondition<>();
        for (Map.Entry<String, Object> entry : context.getData().entrySet()) {
            criterion1.eq(entry.getKey(), entry.getValue());
        }
        DeleteSqlCreator<T> creator = new DeleteSqlCreator<>(context);
        creator.setWhereCriterion(criterion1);
        execute(creator.create(), creator.createParameters());
    }

    @Override
    public <T> int deleteAllById(Class<T> clazz, Iterable<? extends Serializable> keys) {
        TableMetaContext context = TableMetaContext.of(clazz);
        DeleteSqlCreator<T> creator = new DeleteSqlCreator<>(context);
        creator.where(w -> w.in(context.getPrimaryKeyName(), Sets.newHashSet(keys)));
        return execute(creator.create(), creator.createParameters());
    }


    @Override
    public <T> Object insertEntityIgnoreNull(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        InsertSqlCreator creator = new InsertSqlCreator(context, true);
        Object insert = insert(creator.create(), creator.createParameters());
        if (null == insert) {
            insert = context.getPrimaryKeyValue();
        }
        return insert;
    }

    @Override
    public <T> Object insertEntity(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        InsertSqlCreator creator = new InsertSqlCreator(context);
        Object insert = insert(creator.create(), creator.createParameters());
        if (null == insert) {
            insert = context.getPrimaryKeyValue();
        }
        return insert;
    }


    @Override
    public <T> Object saveEntityIgnoreNull(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        context.propertyNotNull();
        context.initData();
        return saveInternal(context, true);
    }

    @Override
    public <T> void saveAll(List<T> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        //超过8000行进行拆分
        List<List<T>> partitions = Lists.partition(rows, 8000);
        partitions.forEach(dataList -> {
            List<TableMetaContext> insertRowsWithoutKey = new ArrayList<>();
            List<TableMetaContext> insertRowsWithKey = new ArrayList<>();
            Map<Object, TableMetaContext> toUpdate = Maps.newLinkedHashMap();
            final List<TableMetaContext> updateList = new ArrayList<>();
            TableMetaContext one = TableMetaContext.of(dataList.get(0));
            for (T data : dataList) {
                TableMetaContext context = TableMetaContext.of(data);
                Object primaryKet = context.getPrimaryKeyValue();
                if (data instanceof PersistEntity) {
                    if (((PersistEntity) data).isNew()) {
                        insertRowsWithoutKey.add(context);
                    } else {
                        updateList.add(context);
                    }
                } else {
                    // auto increment
                    if (primaryKet == null) {
                        insertRowsWithoutKey.add(context);
                    } else {
                        toUpdate.put(primaryKet, context);
                    }
                }
            }
            if (!toUpdate.isEmpty()) {
                Set<Object> ids = toUpdate.keySet();
                List<Object> list = findIdExists(one.getMetaDefinition().getEntityClass(), ids);
                Set<Object> updates = ids.stream().filter(list::contains).collect(Collectors.toSet());
                toUpdate.forEach((key, v) -> {
                    if (updates.contains(key)) {
                        updateList.add(v);
                    } else {
                        insertRowsWithKey.add(v);
                    }
                });
                toUpdate.clear();
            }
            String insertSqlWithoutKey;


            if (insertRowsWithoutKey.isEmpty()) {
                insertSqlWithoutKey = null;
            } else {
                insertSqlWithoutKey = buildInsertWithoutKey(insertRowsWithoutKey);
            }
            String insertSqlWithKey;
            if (insertRowsWithKey.isEmpty()) {
                insertSqlWithKey = null;
            } else {
                insertSqlWithKey = buildInsertWithKey(insertRowsWithKey);
            }
            String updateSql;
            if (!updateList.isEmpty()) {
                TableMetaContext context = updateList.get(0);
                UpdateBatchByIdSqlCreator creator = new UpdateBatchByIdSqlCreator(context);
                updateSql = creator.create();
                updateList.forEach(metaContext -> {
                    metaContext.handleEntityListener(entityListener -> entityListener.preUpdate(metaContext.getEntity()));
                    metaContext.setPreparedParameterNames(creator.createParameterNames());
                });
            } else {
                updateSql = null;
            }

            logger.info("Batch update start,insert-without-key size is:{},insert-with-key size is:{},update size is:{}", insertRowsWithoutKey.size(), insertRowsWithKey.size(), updateList.size());
            if (logger.isDebugEnabled()) {
                logger.debug("Batch update sql:{}", updateSql);
                logger.debug("Batch insert-without-key sql:{}", insertSqlWithoutKey);
                logger.debug("Batch insert-with-key sql:{}", insertSqlWithKey);
            }

            doWork(conn -> {
                batchUpdateInternal(updateSql, updateList, conn, true);
                batchUpdateInternal(insertSqlWithoutKey, insertRowsWithoutKey, conn, false);
                batchUpdateInternal(insertSqlWithKey, insertRowsWithKey, conn, false);
                return null;
            });
        });
        logger.info("Batch update completed.");
    }

    private String buildInsertWithKey(List<TableMetaContext> insertRowsWithKey) {
        String insertSqlWithKey;
        TableMetaContext context = insertRowsWithKey.get(0);
        InsertSqlCreator insertSqlCreator = new InsertSqlCreator(context);
        insertSqlWithKey = insertSqlCreator.create();
        insertRowsWithKey.forEach(metaContext -> {
            metaContext.handleEntityListener(entityListener -> entityListener.prePersist(metaContext.getEntity()));
            metaContext.setPreparedParameterNames(insertSqlCreator.createParameterNames());
        });
        return insertSqlWithKey;
    }

    private String buildInsertWithoutKey(List<TableMetaContext> insertRowsWithoutKey) {
        String insertSqlWithoutKey;
        TableMetaContext context = insertRowsWithoutKey.get(0);
        InsertSqlCreator creator = new InsertSqlCreator(context);
        creator.setGeneratedKeyNames(Sets.newHashSet(context.getPrimaryKeyName()));
        insertSqlWithoutKey = creator.create();
        insertRowsWithoutKey.forEach(row -> {
            row.handleEntityListener(entityListener -> entityListener.prePersist(row.getEntity()));

            row.setPreparedParameterNames(creator.createParameterNames());
        });
        return insertSqlWithoutKey;
    }

    @Override
    public void runScript(@NonNull Reader reader) {
        jdbcExecutor.runScript(reader);
    }


    @Override
    public <T> Object saveEntity(T entity) {
        TableMetaContext context = TableMetaContext.of(entity);
        return saveInternal(context, false);
    }


    @Override
    public void dropTables(Class<?>... arr) throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("dropTables query is not supported yet.");
    }

    @Override
    public void createTables(Class<?>... arr) throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("createTables query is not supported yet.");
    }

    private Object saveInternal(TableMetaContext context, boolean skipNull) {
        Map<String, Object> data = context.getData();
        Object primaryKeyValue = context.getPrimaryKeyValue();
        String primaryKeyName = context.getPrimaryKeyName();
        EntityTableMetaDefinition metaDefinition = context.getMetaDefinition();
        if (null == primaryKeyValue) {
            InsertSqlCreator creator = new InsertSqlCreator(context, skipNull);
            return insert(creator.create(), creator.createParameters());
        }
        boolean entityListener = context.isEntityListener();

        Object value = querySingleValue(StringUtils.format("select {} from {} where {} = ?", primaryKeyName, context.getTableName(), primaryKeyName), Object.class, primaryKeyValue);

        if (null == value) {
            if (entityListener) {
                ((EntityListener) context.getEntity()).prePersist(context.getEntity());
            }

            InsertSqlCreator creator = new InsertSqlCreator(context, skipNull);
            insert(creator.create(), creator.createParameters());
            if (entityListener) {
                ((EntityListener) context.getEntity()).postPersist(context.getEntity());
            }
        } else {
            if (entityListener) {
                ((EntityListener) context.getEntity()).preUpdate(context.getEntity());
            }
            UpdateSqlCreator<?> creator = new UpdateSqlCreator<>(context, skipNull);
            creator.where(w -> w.eq(primaryKeyName, primaryKeyValue));
            execute(creator.create(), creator.createParameters());
            if (entityListener) {
                ((EntityListener) context.getEntity()).postUpdate(context.getEntity());
            }
        }
        return data.get(metaDefinition.getPrimaryKey());
    }

    private void batchUpdateInternal(String sql, List<TableMetaContext> rows, Connection connection, boolean isUpdate) throws SQLException {
        if (null == sql || rows.isEmpty()) {
            return;
        }
        boolean entityListener = rows.get(0).isEntityListener();
        BatchPreparedStatementSetter setter = new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TableMetaContext table = rows.get(i);
                Map<String, Object> data;
                Map<String, Object> map = table.getData();
                if (CollectionUtils.isEmpty(table.getPreparedParameterNames())) {
                    data = table.getData();
                } else {
                    data = new LinkedHashMap<>();
                    for (String name : table.getPreparedParameterNames()) {
                        data.put(name, map.get(name));
                    }
                }
                EntityTableMetaDefinition metaDefinition = table.getMetaDefinition();
                int colIndex = 0;
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    Object value = entry.getValue();
                    colIndex++;

                    int type = SqlDataUtils.javaTypeToSqlParameterType(metaDefinition.findFieldByColumn(entry.getKey()).getType());

                    SqlParameterValue paramValue = new SqlParameterValue(type, value);
                    SqlDataUtils.setParameterValue(ps, colIndex, paramValue, paramValue.getValue());
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        };
        PreparedStatement ps = connection.prepareStatement(sql);
        int batchSize = setter.getBatchSize();
        if (JdbcUtils.supportsBatchUpdates(ps.getConnection())) {
            for (int j = 0; j < batchSize; j++) {
                setter.setValues(ps, j);
                ps.addBatch();
                if ((j + 1) % JoyaJdbc.config().getBatchSize() == 0 || j == batchSize - 1) {
                    ps.executeBatch();
                    ps.clearBatch();
                    if (entityListener) {
                        List<TableMetaContext> list = rows.subList(Math.max(j + 1 - JoyaJdbc.config().getBatchSize(), 0), j + 1);
                        list.forEach(row -> {
                            if (isUpdate) {
                                ((EntityListener) row.getEntity()).postUpdate(row.getEntity());
                            } else {
                                ((EntityListener) row.getEntity()).postPersist(row.getEntity());
                            }
                        });
                    }
                }
            }
        } else {
            for (int i = 0; i < rows.size(); i++) {
                setter.setValues(ps, i);
                ps.executeUpdate();
                if (entityListener) {
                    if (isUpdate) {
                        ((EntityListener) rows.get(i).getEntity()).postUpdate(rows.get(i).getEntity());
                    } else {
                        ((EntityListener) rows.get(i).getEntity()).postPersist(rows.get(i).getEntity());
                    }
                }
            }
        }
        if (!ps.isClosed()) {
            ps.close();
        }
    }

    /**
     * 创建语句
     *
     * @param conn   连接
     * @param sql    sql语句
     * @param params sql参数
     * @return 创建的PreparedStatement对象
     * @throws SQLException 来自JDBC的异常
     */
}
