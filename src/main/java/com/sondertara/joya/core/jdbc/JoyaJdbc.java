package com.sondertara.joya.core.jdbc;


import com.sondertara.common.util.StringFormatter;
import com.sondertara.joya.core.model.TableEntity;
import com.sondertara.joya.utils.ClassUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * JDBC工具类
 *
 * @author huangxiaohu
 */
public class JoyaJdbc {

    private static final Logger LOG = LoggerFactory.getLogger(JoyaJdbc.class);
    private final ConnectionManager connManager;

    /**
     * 创建JdbcUtils
     *
     * @param dataSource 数据源
     */
    public JoyaJdbc(DataSource dataSource) {
        connManager = new ConnectionManager(dataSource);
    }

    /**
     * 自定义DataSource 销毁
     *
     * @param consumer func
     */
    public void destroy(Consumer<DataSource> consumer) {
        this.connManager.destroy(consumer);
    }

    /**
     * 判断当前是否在事务中
     */
    public boolean inTransaction() {
        return connManager.inTransaction();
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
     * @see RecordMapper
     * @see ListRecordMapper
     * @see SingleRowRecordMapper
     */
    public <T> T query(String sql, RecordMapper<T> recordMapper, Object... params) {
        ResultSet rs = null;
        PreparedStatement stmt = null;
        Connection conn = null;
        try {
            conn = connManager.getConnection();
            stmt = createPreparedStatement(conn, sql, params);
            rs = stmt.executeQuery();
            return recordMapper.map(new RecordAdapterForResultSet(rs));
        } catch (Exception e) {
            LOG.error("query sql error,sql:{}", sql, e);
            throw new DbException(e.getMessage(), e);
        } finally {
            connManager.close(conn, stmt, rs);
        }
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
     * @see RowMapper
     * @see BeanRowMapper
     * @see MapRowMapper
     * @see SingleColumnRowMapper
     */
    public <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... params) {
        return query(sql, new ListRecordMapper<>(rowMapper), params);
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
    public <T> List<T> queryList(String sql, Class<T> type, Object... params) {
        return query(sql, new ListRecordMapper<>(new BeanRowMapper<>(type)), params);
    }

    /**
     * 查询数据库，返回结果集中的单个值。
     * 如果结果集中有多个值，则只返回第一行第一列的值。
     *
     * @param sql    sql语句
     * @param params sql参数
     * @param <T>    结果类型
     * @return 成功则返回结果值，失败则抛出DbException，结果为空则返回null
     */
    public <T> T querySingleValue(String sql, Object... params) {
        return query(sql, new SingleRowRecordMapper<>(new SingleColumnRowMapper<>()), params);
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
    public <T> T querySingleRow(String sql, RowMapper<T> rowMapper, Object... params) {
        return query(sql, new SingleRowRecordMapper<>(rowMapper), params);
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
    public <T> T querySingleRow(String sql, Class<T> type, Object... params) {
        return querySingleRow(sql, new BeanRowMapper<>(type), params);
    }

    /**
     * 更新数据库，返回影响行数
     *
     * @param sql sql语句
     * @return 成功则返回影响行数，失败则抛出DbException
     */
    public int update(String sql) {
        Connection conn = null;
        Statement stmt = null;

        try {
            conn = connManager.getConnection();
            stmt = conn.createStatement();
            return stmt.executeUpdate(sql);
        } catch (Exception e) {
            LOG.error("update sql error,sql:{}", sql, e);
            throw new DbException(e.getMessage(), e);
        } finally {
            connManager.close(conn, stmt);
        }
    }

    /**
     * 更新数据库，返回影响行数
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回影响行数，失败则抛出DbException
     */
    public int update(String sql, Object[] params) {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = connManager.getConnection();
            stmt = createPreparedStatement(conn, sql, params);
            return stmt.executeUpdate();
        } catch (Exception e) {
            throw new DbException(e.getMessage(), e);
        } finally {
            connManager.close(conn, stmt);
        }
    }

    /**
     * 插入数据库,返回主键值
     *
     * @param sql    sql语句
     * @param params sql参数
     * @return 成功则返回主键值，失败则抛出DbException
     */
    public Object insert(String sql, Object... params) {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = connManager.getConnection();
            stmt = createPreparedStatement(conn, sql, params);
            stmt.executeUpdate();
            return stmt.getGeneratedKeys().getObject(1);
        } catch (Exception e) {
            throw new DbException(e.getMessage(), e);
        } finally {
            connManager.close(conn, stmt);
        }
    }

    public <T> Object saveEntityIgnoreNull(T entity) {
        TableEntity table = ClassUtils.getTable(entity.getClass(),true);
        Map<String, Object> data = table.getData();
        Map<String, Object> newData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() != null) {
                newData.put(entry.getKey(), entry.getValue());
            }
        }
        table.setData(newData);
        return save(table);
    }


    public <T> Object saveEntity(T entity) {
        TableEntity table = ClassUtils.getTable(entity,true);
        return save(table);

    }

    /**
     * 执行sql命令, 失败则抛出DbException
     *
     * @param sql sql语句
     */
    public void execute(String sql) {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = connManager.getConnection();
            stmt = conn.createStatement();
            stmt.execute(sql);
        } catch (Exception e) {
            LOG.error("execute sql error,sql:{}", sql, e);
            throw new DbException(e.getMessage(), e);
        } finally {
            connManager.close(conn, stmt);
        }
    }

    public Connection getConnection() {
        return connManager.getConnection();
    }

    /**
     * 开启事务
     */
    public Connection startTransaction() {
        connManager.startTransaction();
        return connManager.getConnection();
    }

    /**
     * 提交事务
     */
    public void commit() {
        connManager.commit();
    }

    /**
     * 回滚事务
     */
    public void rollback() {
        connManager.rollback();
    }

    /**
     * run transaction with connection
     *
     * @param action callback
     * @param <T>    callback type
     * @return result
     */
    public <T> T doInTransaction(ConnectionCallback<T> action) {

        Connection conn = null;
        try {
            conn = this.startTransaction();
            T t = action.doInTransaction(conn);
            this.commit();
            return t;
        } catch (Exception e) {
            LOG.error("TransactionCallback", e);
            this.rollback();
            throw new DbException("TransactionCallback", e);
        } finally {
            connManager.close(conn, null);
        }
    }

    /**
     * run transaction with statement.
     *
     * @param action statement callback
     * @param <T>    type of result
     * @return result
     */
    public <T> T doInStatement(StatementCallback<T> action) {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = this.startTransaction();
            stmt = conn.createStatement();
            T t = action.doInStatement(stmt);
            this.commit();
            return t;
        } catch (Exception e) {
            LOG.error("TransactionCallback", e);
            connManager.close(conn, stmt);
            throw new DbException("TransactionCallback", e);
        } finally {
            connManager.close(conn, stmt);
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
    private PreparedStatement createPreparedStatement(Connection conn, String sql, Object... params) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(sql);
        if (null != params && params.length > 0) {
            for (int i = 0; i < params.length; ++i) {
                stmt.setObject(i + 1, params[i]);
            }
        }
        return stmt;
    }

    private Object save(TableEntity table) {
        Map<String, Object> data = table.getData();
        if (null == data.get(table.getPrimaryKey())) {
            data.remove(table.getPrimaryKey());
            List<String> set = data.keySet().stream().map(s -> "?").collect(Collectors.toList());
            return insert(StringFormatter.format("insert into {}({}) values({})", table.getTableName(), String.join(",", data.keySet()), String.join(",", set)), data.values().toArray());
        }
        Object value = querySingleValue(StringFormatter.format("select {} from {} where {} = ?", table.getPrimaryKey(), table.getTableName(), table.getPrimaryKey()), data.get(table.getPrimaryKey()));
        List<String> set = data.keySet().stream().map(s -> "?").collect(Collectors.toList());
        if (null == value) {
            return insert(StringFormatter.format("insert into {}({}) values({})", table.getTableName(), String.join(",", data.keySet()), String.join(",", set)), data.values().toArray());
        } else {
            StringJoiner sj = new StringJoiner(", ");
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getKey().equals(table.getPrimaryKey())) {
                    continue;
                }
                sj.add("set " + entry.getKey() + " = ?");
                values.add(entry.getValue());
            }
            values.add(data.get(table.getPrimaryKey()));
            update(StringFormatter.format("update {} {} where {} = ?", table.getTableName(), sj.toString(), table.getPrimaryKey()), values.toArray());
            return data.get(table.getPrimaryKey());
        }
    }
}
