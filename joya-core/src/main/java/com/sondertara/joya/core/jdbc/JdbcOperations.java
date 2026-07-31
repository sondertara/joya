package com.sondertara.joya.core.jdbc;

import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ListRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleRowRecordExtractor;
import com.sondertara.joya.core.jdbc.statment.BatchPreparedStatementSetter;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageParam;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Stream;

/**
 * jdbc 操作
 *
 * @author huangxiaohu.1ih
 * @date 2024/11/25 10:20
 */
public interface JdbcOperations {

    /**
     * 获取一个直连 Connection（来自线程绑定的 ConnectionContext）。
     * 不属于事务 API；事务控制请使用 {@link com.sondertara.joya.core.jdbc.transaction.TransactionManager}。
     */
    Connection getConnection();


    /**
     * run sql script
     *
     * @param reader
     */
    void runScript(@NonNull Reader reader);



    /* ----------------------------------------------------------------select---------------------------------------------------------------- */

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
    public <T> T query(String sql, ResultSetExtractor<T> recordMapper, Object... params);

    /**
     * query stream
     *
     * @param sql       the sql
     * @param rowMapper row mapper
     * @param params    the query params
     * @param <T>       the return type
     * @return the stream
     */
    public <T> Stream<T> queryStream(String sql, RowMapper<T> rowMapper, Object... params);


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
    public <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... params);

    /**
     * 查询数据库，将结果集的每一行转换成JavaBean，然后将所有行封装成列表。
     *
     * @param sql    sql语句
     * @param type   JavaBean类型
     * @param params sql参数
     * @param <T>    JavaBean类型
     * @return 成功则返回结果列表，失败则抛出DbException，结果为空则返回空列表
     */
    public <T> List<T> queryList(String sql, Class<T> type, Object... params);

    /**
     * 查询数据库，返回结果集中的单个值。
     * 如果结果集中有多个值，则只返回第一行第一列的值。
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回结果值，失败则抛出DbException，结果为空则返回null
     */
    public <T> T querySingleValue(String sql, Class<T> clazz, Object... params);

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
    public <T> T querySingleRow(String sql, RowMapper<T> rowMapper, Object... params);

    /**
     * 查询数据库，将结果集中的单行数据转换成JavaBean。
     *
     * @param sql    sql语句
     * @param type   JavaBean类型
     * @param params sql参数
     * @param <T>    JavaBean类型
     * @return 成功则返回结果，失败则抛出DbException，结果为空则返回null
     */
    public <T> T querySingleRow(String sql, Class<T> type, Object... params);


    <T> List<T> queryTop(String sql, int top, RowMapper<T> rowMapper, Object... params);

    <T> List<T> queryTop(String sql, int top, Class<T> tClass, Object... params);

    <T> PageResult<T> queryPage(String sqlStr, RowMapper<T> rowMapper, PageParam page, Object... params);

    <T> PageResult<T> queryPage(String sql, Class<T> tClass, PageParam page, Object... params);


    /**
     * 插入数据库,如果是自增主键就返回主键值，否者返回null
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回主键值，失败则抛出DbException
     */
    @Nullable
    public Object insert(String sql,  Object[] params);



    /**
     * jdbc 执行sql
     *
     * @param sql
     * @param params
     * @return
     */
    public int execute(String sql, Object[] params);


    /**
     * 执行sql命令, 失败则抛出DbException
     *
     * @param sql sql语句
     */
    public int execute(String sql);

    int[] batchUpdate(String sql, BatchPreparedStatementSetter pss);

    /**
     * Execute a batch using the supplied SQL statement with the batch of supplied arguments.
     *
     * @param sql       the SQL statement to execute
     * @param batchArgs the List of Object arrays containing the batch of arguments for the query
     * @return an array containing the numbers of rows affected by each update in the batch
     * (may also contain special JDBC-defined negative values for affected rows such as
     * {@link java.sql.Statement#SUCCESS_NO_INFO}/{@link java.sql.Statement#EXECUTE_FAILED})
     */
    int[] batchUpdate(String sql, List<Object[]> batchArgs);

    /**
     * Execute a batch using the supplied SQL statement with the batch of supplied arguments.
     *
     * @param sql       the SQL statement to execute.
     * @param batchArgs the List of Object arrays containing the batch of arguments for the query
     * @param argTypes  the SQL types of the arguments
     *                  (constants from {@code java.sql.Types})
     * @return an array containing the numbers of rows affected by each update in the batch
     * (may also contain special JDBC-defined negative values for affected rows such as
     * {@link java.sql.Statement#SUCCESS_NO_INFO}/{@link java.sql.Statement#EXECUTE_FAILED})
     */
    int[] batchUpdate(String sql, List<Object[]> batchArgs, int[] argTypes);
}
