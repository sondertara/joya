package com.sondertara.joya.core.jdbc.executor;

import com.sondertara.common.base.Assert;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.text.StringUtils;
import com.sondertara.common.timing.Stopwatch;
import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.callback.PreparedStatementCallback;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ListRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleRowRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.StreamRecordExtractor;
import com.sondertara.joya.core.jdbc.statment.BatchPreparedStatementSetter;
import com.sondertara.joya.core.jdbc.statment.PreparedStatementCreator;
import com.sondertara.joya.core.jdbc.statment.SqlProvider;
import com.sondertara.joya.core.jdbc.support.JdbcAccessor;
import com.sondertara.joya.core.jdbc.support.JdbcType;
import com.sondertara.joya.core.jdbc.support.LoggingOutputStream;
import com.sondertara.joya.core.jdbc.support.ScriptRunner;
import com.sondertara.joya.core.jdbc.support.SqlParameterValue;
import com.sondertara.joya.core.jdbc.support.TransactionSynchronization;
import com.sondertara.joya.core.jdbc.support.dialect.DbType;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 原始 JDBC 执行器，只负责“拿到连接 → 执行 SQL → 归还连接”。
 * <p>
 * 从原 {@code JoyaJdbc} 中抽取，与业务无关：不感知实体、链式查询、批量切批等业务逻辑。
 * 所有连接均通过 {@link TransactionSynchronization} 获取与释放，保证与事务引用计数兼容。
 *
 * @author huangxiaohu
 */
public class JdbcExecutor extends JdbcAccessor {

    private static final Logger logger = LoggerFactory.getLogger(JdbcExecutor.class);

    public JdbcExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection obtainConnection() {
        return TransactionSynchronization.getOrCreate(this.dataSource).getConnection();
    }

    public <T> T doWork(ConnectionCallback<T> action) {
        Connection conn = null;
        try {
            conn = obtainConnection();
            return action.doWork(conn);
        } catch (Exception e) {
            logger.error("JdbcExecutor#doWork error, {}", e.getMessage(), e);
            throw new DbException(e);
        } finally {
            TransactionSynchronization.release(dataSource);
        }
    }

    public <T> T query(String sql, ResultSetExtractor<T> recordMapper, Object... params) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        if (logger.isDebugEnabled()) {
            logger.debug("JdbcExecutor#query start....");
        }
        return doWork(conn -> {
            try (PreparedStatement stmt = createPreparedStatement(conn, sql, false, params);
                 ResultSet rs = stmt.executeQuery()) {
                return recordMapper.extract(rs);
            } finally {
                stopwatch.stop();
                if (logger.isDebugEnabled()) {
                    logger.debug("JdbcExecutor#query end, cost {}....", stopwatch.elapsed(TimeUnit.MILLISECONDS));
                }
            }
        });
    }

    public <T> Stream<T> queryStream(String sql, RowMapper<T> rowMapper, Object... params) {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rs = null;
        try {
            connection = obtainConnection();
            statement = createPreparedStatement(connection, sql, false, params);
            statement.setFetchSize(2000);
            rs = statement.executeQuery();
            final DataSource ds = this.dataSource;
            final Statement capturedStatement = statement;
            StreamRecordExtractor<T> recordMapper = new StreamRecordExtractor<>(rowMapper);
            recordMapper.onClose(() -> {
                closeQuietly(rs);
                closeQuietly(capturedStatement);
                TransactionSynchronization.release(ds);
            });
            return recordMapper.extract(rs);
        } catch (SQLException e) {
            closeQuietly(rs);
            closeQuietly(statement);
            TransactionSynchronization.release(dataSource);
            throw new DbException(e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    public <T> List<T> queryList(String sql, RowMapper<T> rowMapper, Object... params) {
        return query(sql, new ListRecordExtractor<>(rowMapper), params);
    }

    public <T> List<T> queryList(String sql, Class<T> type, Object... params) {
        return query(sql, new ListRecordExtractor<>(new BeanRowMapper<>(type)), params);
    }

    public <T> T querySingleValue(String sql, Class<T> clazz, Object... params) {
        return query(sql, new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(clazz)), params);
    }

    public <T> T querySingleRow(String sql, RowMapper<T> rowMapper, Object... params) {
        return query(sql, new SingleRowRecordExtractor<>(rowMapper), params);
    }

    public <T> T querySingleRow(String sql, Class<T> type, Object... params) {
        return querySingleRow(sql, new BeanRowMapper<>(type), params);
    }

    public int execute(String sql) {
        return doWork(conn -> {
            try (Statement stmt = conn.createStatement()) {
                return stmt.executeUpdate(sql);
            }
        });
    }

    public int execute(String sql, Object[] params) {
        return doWork(conn -> {
            try (PreparedStatement stmt = createPreparedStatement(conn, sql, false, params)) {
                return stmt.executeUpdate();
            }
        });
    }

    public Object insert(String sql, Object[] params) {
        return doWork(conn -> {
            try (PreparedStatement stmt = createPreparedStatement(conn, sql, true, params)) {
                stmt.executeUpdate();
                ResultSet generatedKeys = stmt.getGeneratedKeys();
                if (generatedKeys.next()) {
                    return generatedKeys.getObject(1);
                }
                return null;
            }
        });
    }

    public <T> T execute(String sql, PreparedStatementCallback<T> action) throws DbException {
        return execute(new SimplePreparedStatementCreator(sql), action, true);
    }

    public <T> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action, boolean closeResources) {
        Assert.notNull(psc, "PreparedStatementCreator must not be null");
        Assert.notNull(action, "Callback object must not be null");
        if (logger.isDebugEnabled()) {
            String sql = getSql(psc);
            logger.debug("Executing prepared SQL statement" + (sql != null ? " [" + sql + "]" : ""));
        }

        Connection con = obtainConnection();
        PreparedStatement ps = null;
        try {
            ps = psc.createPreparedStatement(con);
            return action.doInPreparedStatement(ps);
        } catch (SQLException ex) {
            JdbcUtils.closeStatement(ps);
            ps = null;
            TransactionSynchronization.release(dataSource);
            con = null;
            throw new DbException(ex.getCause());
        } finally {
            if (closeResources) {
                JdbcUtils.closeStatement(ps);
                TransactionSynchronization.release(dataSource);
            }
        }
    }

    private static String getSql(Object obj) {
        return (obj instanceof SqlProvider ? ((SqlProvider) obj).getSql() : null);
    }

    private PreparedStatementCallback<int[]> getPreparedStatementCallback(BatchPreparedStatementSetter pss) {
        return ps -> {
            int batchSize = pss.getBatchSize();
            if (JdbcUtils.supportsBatchUpdates(ps.getConnection())) {
                for (int i = 0; i < batchSize; i++) {
                    pss.setValues(ps, i);
                    ps.addBatch();
                }
                return ps.executeBatch();
            } else {
                List<Integer> rowsAffected = new ArrayList<>();
                for (int i = 0; i < batchSize; i++) {
                    pss.setValues(ps, i);
                    rowsAffected.add(ps.executeUpdate());
                }
                int[] rowsAffectedArray = new int[rowsAffected.size()];
                for (int i = 0; i < rowsAffectedArray.length; i++) {
                    rowsAffectedArray[i] = rowsAffected.get(i);
                }
                return rowsAffectedArray;
            }
        };
    }

    public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) throws DbException {
        if (logger.isDebugEnabled()) {
            logger.debug("Executing SQL batch update [" + sql + "]");
        }
        int batchSize = pss.getBatchSize();
        if (batchSize == 0) {
            return new int[0];
        }

        int[] result = execute(sql, getPreparedStatementCallback(pss));
        Assert.state(result != null, "No result array");
        return result;
    }

    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        return batchUpdate(sql, batchArgs, new int[0]);
    }

    public int[] batchUpdate(String sql, List<Object[]> batchArgs, int[] argTypes) {
        if (batchArgs.isEmpty()) {
            return new int[0];
        }
        return batchUpdate(
                sql,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        Object[] values = batchArgs.get(i);
                        int colIndex = 0;
                        for (Object value : values) {
                            colIndex++;
                            if (value instanceof SqlParameterValue) {
                                SqlParameterValue paramValue = (SqlParameterValue) value;
                                SqlDataUtils.setParameterValue(ps, colIndex, paramValue, paramValue.getValue());
                            } else {
                                int colType;
                                if (argTypes.length < colIndex) {
                                    colType = JdbcType.UNKNOWN.getCodes()[0];
                                } else {
                                    colType = argTypes[colIndex - 1];
                                }
                                SqlDataUtils.setParameterValue(ps, colIndex, colType, value);
                            }
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return batchArgs.size();
                    }
                });
    }

    public void runScript(@NonNull Reader reader) {
        try (Connection connection = obtainConnection();
             PrintWriter printWriter = new PrintWriter(new LoggingOutputStream(logger, Level.INFO));
             PrintWriter errorWriter = new PrintWriter(new LoggingOutputStream(logger, Level.ERROR))) {
            ScriptRunner scriptRunner = new ScriptRunner(connection);
            scriptRunner.setStopOnError(true);
            scriptRunner.setThrowWarning(true);
            scriptRunner.setLogWriter(printWriter);
            scriptRunner.setErrorLogWriter(errorWriter);
            scriptRunner.runScript(reader);
        } catch (Exception e) {
            throw new DbException(e);
        } finally {
            TransactionSynchronization.release(dataSource);
        }
    }

    public static PreparedStatement createPreparedStatement(Connection conn, String sql, boolean autoGeneratedKeys, Object... params) throws SQLException {
        PreparedStatement stmt;
        String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(sql);
        if (autoGeneratedKeys) {
            stmt = conn.prepareStatement(jdbcStyleSql, Statement.RETURN_GENERATED_KEYS);
        } else {
            stmt = conn.prepareStatement(jdbcStyleSql);
        }
        if (null != params && params.length > 0) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof java.sql.Date) {
                    stmt.setDate(i + 1, (java.sql.Date) params[i]);
                } else if (params[i] instanceof Timestamp) {
                    stmt.setTimestamp(i + 1, (Timestamp) params[i]);
                } else if (params[i] instanceof Date) {
                    stmt.setTimestamp(i + 1, Timestamp.from(((Date) params[i]).toInstant()));
                } else if (params[i] instanceof OffsetDateTime) {
                    if (DbType.oracle.equals(JdbcUtils.getDatabaseType(conn))) {
                        OffsetDateTime offsetDateTime = (OffsetDateTime) params[i];
                        if (offsetDateTime.getYear() < 1905) {
                            stmt.setTimestamp(i + 1, Timestamp.valueOf(offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()));
                        } else {
                            stmt.setTimestamp(i + 1, Timestamp.from(offsetDateTime.toInstant()));
                        }
                    } else {
                        stmt.setObject(i + 1, params[i]);
                    }
                } else {
                    stmt.setObject(i + 1, params[i]);
                }
            }
        }
        return stmt;
    }

    private static class SimplePreparedStatementCreator implements PreparedStatementCreator, SqlProvider {

        private final String sql;

        public SimplePreparedStatementCreator(String sql) {
            Assert.notNull(sql, "SQL must not be null");
            this.sql = sql;
        }

        @Override
        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            return con.prepareStatement(this.sql);
        }

        @Override
        public String getSql() {
            return this.sql;
        }
    }
}
