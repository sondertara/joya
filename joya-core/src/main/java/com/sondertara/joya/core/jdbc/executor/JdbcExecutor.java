package com.sondertara.joya.core.jdbc.executor;

import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.resultset.mapper.ListRecordExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.StreamRecordExtractor;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.LoggingOutputStream;
import com.sondertara.joya.core.jdbc.support.ScriptRunner;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.JdbcUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

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
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * JDBC 执行器：只负责"借连接 → 执行 SQL → 还连接"。
 *
 * <p>连接一律经 {@link ConnectionProvider} 借还，执行器本身不读取任何线程级状态，
 * 也不感知事务：事务与否由注入的 {@link ConnectionProvider} 决定。
 *
 * @author huangxiaohu
 */
public class JdbcExecutor implements Executor {

    private static final Logger logger = LoggerFactory.getLogger(JdbcExecutor.class);

    private final ConnectionProvider connections;

    public JdbcExecutor(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "connectionProvider must not be null");
    }

    public ConnectionProvider connections() {
        return connections;
    }

    // ===== 连接作用域 =====

    @Override
    public <T> T withConnection(ConnectionCallback<T> action) {
        Connection conn = null;
        try {
            conn = connections.acquire();
            return action.doWork(conn);
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            releaseQuietly(conn);
        }
    }

    private void releaseQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            connections.release(conn);
        } catch (SQLException e) {
            logger.warn("Failed to release connection", e);
        }
    }

    // ===== Executor 端口实现 =====

    @Override
    public <T> T query(SqlStatement statement, ResultSetExtractor<T> extractor) {
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, statement, false);
                 ResultSet rs = ps.executeQuery()) {
                return extractor.extract(rs);
            }
        });
    }

    @Override
    public <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper) {
        return query(statement, new ListRecordExtractor<>(mapper));
    }

    @Override
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = connections.acquire();
            ps = prepare(conn, statement, false);
            ps.setFetchSize(2000);
            rs = ps.executeQuery();
            final Connection capturedConn = conn;
            final PreparedStatement capturedPs = ps;
            final ResultSet capturedRs = rs;
            StreamRecordExtractor<T> extractor = new StreamRecordExtractor<>(mapper);
            // 流关闭时才归还连接：调用方必须在 try-with-resources 中使用返回的 Stream
            extractor.onClose(() -> {
                closeQuietly(capturedRs);
                closeQuietly(capturedPs);
                releaseQuietly(capturedConn);
            });
            return extractor.extract(capturedRs);
        } catch (SQLException e) {
            closeQuietly(rs);
            closeQuietly(ps);
            releaseQuietly(conn);
            throw new DbException(e);
        }
    }

    @Override
    public int update(SqlStatement statement) {
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, statement, false)) {
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public Object insert(SqlStatement statement) {
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, statement, true)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys != null && keys.next() ? keys.getObject(1) : null;
                }
            }
        });
    }

    @Override
    public int[] batchUpdate(BatchSqlStatement batch) {
        if (batch.rowCount() == 0) {
            return new int[0];
        }
        List<ColumnBinding> columns = batch.columns();
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, batch.sql(), false)) {
                for (Object[] row : batch.rows()) {
                    for (int c = 0; c < columns.size(); c++) {
                        ColumnBinding column = columns.get(c);
                        column.binder().bind(ps, c + 1,
                                SqlParameter.of(c + 1, column.name(), row[c], ParameterSource.RAW));
                    }
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        });
    }

    @Override
    public int[] batchUpdate(SqlStatement template, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, template.sql(), false)) {
                for (Object[] row : rows) {
                    bind(conn, ps, row);
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        });
    }

    @Override
    public void runScript(Reader reader) {
        // 注意：不能用 try-with-resources 关闭连接——事务模式下连接属于事务，由 ConnectionProvider 决定如何归还
        withConnection(conn -> {
            try (PrintWriter logWriter = new PrintWriter(new LoggingOutputStream(logger, Level.INFO));
                 PrintWriter errorWriter = new PrintWriter(new LoggingOutputStream(logger, Level.ERROR))) {
                ScriptRunner runner = new ScriptRunner(conn);
                runner.setStopOnError(true);
                runner.setThrowWarning(true);
                runner.setLogWriter(logWriter);
                runner.setErrorLogWriter(errorWriter);
                runner.runScript(reader);
                return null;
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw new DbException(e);
            }
        });
    }

    // ===== 语句准备 =====

    private PreparedStatement prepare(Connection conn, SqlStatement statement, boolean generatedKeys) throws SQLException {
        String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(statement.sql());
        PreparedStatement ps = generatedKeys
                ? conn.prepareStatement(jdbcStyleSql, Statement.RETURN_GENERATED_KEYS)
                : conn.prepareStatement(jdbcStyleSql);
        try {
            List<SqlParameter> parameters = statement.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                SqlParameter param = parameters.get(i);
                param.binder().bind(ps, i + 1, param);
            }
            OptionalInt timeout = connections.queryTimeoutSeconds();
            if (timeout.isPresent()) {
                ps.setQueryTimeout(timeout.getAsInt());
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(ps);
            throw e;
        }
    }

    private PreparedStatement prepare(Connection conn, String sql, boolean generatedKeys, Object... params) throws SQLException {
        PreparedStatement ps = createPreparedStatement(conn, sql, generatedKeys, params);
        OptionalInt timeout = connections.queryTimeoutSeconds();
        if (timeout.isPresent()) {
            ps.setQueryTimeout(timeout.getAsInt());
        }
        return ps;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 清理阶段异常不掩盖主流程
            }
        }
    }

    /**
     * 创建并绑定预处理语句（支持 {@code ?1 ?2} 序号占位符，自动转为 JDBC 风格）。
     */
    public static PreparedStatement createPreparedStatement(Connection conn, String sql, boolean autoGeneratedKeys,
                                                            Object... params) throws SQLException {
        String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(sql);
        PreparedStatement stmt = autoGeneratedKeys
                ? conn.prepareStatement(jdbcStyleSql, Statement.RETURN_GENERATED_KEYS)
                : conn.prepareStatement(jdbcStyleSql);
        try {
            bind(conn, stmt, params);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(stmt);
            throw e;
        }
        return stmt;
    }

    private static void bind(Connection conn, PreparedStatement stmt, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value instanceof java.sql.Date date) {
                stmt.setDate(index, date);
            } else if (value instanceof Timestamp timestamp) {
                stmt.setTimestamp(index, timestamp);
            } else if (value instanceof Date date) {
                stmt.setTimestamp(index, Timestamp.from(date.toInstant()));
            } else if (value instanceof OffsetDateTime odt && DbType.oracle.equals(JdbcUtils.getDatabaseType(conn))) {
                if (odt.getYear() < 1905) {
                    stmt.setTimestamp(index, Timestamp.valueOf(odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()));
                } else {
                    stmt.setTimestamp(index, Timestamp.from(odt.toInstant()));
                }
            } else {
                stmt.setObject(index, value);
            }
        }
    }
}
