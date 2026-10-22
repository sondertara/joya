package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.executor.ListRecordExtractor;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.StreamRecordExtractor;
import com.sondertara.joya.core.kernel.support.LoggingOutputStream;
import com.sondertara.joya.core.kernel.support.ScriptRunner;
import com.sondertara.joya.core.kernel.ExecutionOptions;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.ParameterBinder;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.exceptions.DbException;
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
import java.util.ArrayList;
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
        return batchExecute(batch).batchCounts();
    }

    @Override
    public BatchSlice batchExecute(BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        List<ColumnBinding> columns = batch.columns();
        List<Object[]> rows = batch.rows();
        int totalRows = rows.size();
        int chunkSize = batch.batchSize() > 0 ? batch.batchSize() : ExecutionOptions.DEFAULT_BATCH_SIZE;
        boolean wantKeys = batch.returnGeneratedKeys();

        return withConnection(conn -> {
            int[] allCounts = new int[totalRows];
            int countOffset = 0;
            List<Object> generatedKeys = wantKeys ? new ArrayList<>(totalRows) : List.of();

            try (PreparedStatement ps = prepare(conn, batch.sql(), wantKeys)) {
                int inChunk = 0;
                for (int r = 0; r < totalRows; r++) {
                    Object[] row = rows.get(r);
                    for (int c = 0; c < columns.size(); c++) {
                        ColumnBinding column = columns.get(c);
                        column.binder().bindValue(ps, c + 1, column.name(), row[c]);
                    }
                    ps.addBatch();
                    inChunk++;
                    if (inChunk >= chunkSize) {
                        countOffset = flushChunk(ps, allCounts, countOffset, wantKeys, generatedKeys);
                        inChunk = 0;
                    }
                }
                if (inChunk > 0) {
                    flushChunk(ps, allCounts, countOffset, wantKeys, generatedKeys);
                }
                return BatchSlice.of(allCounts, generatedKeys);
            }
        });
    }

    @Override
    public int[] batchUpdate(SqlStatement template, List<Object[]> rows) {
        return batchUpdate(template, rows, ExecutionOptions.DEFAULT_BATCH_SIZE);
    }

    public int[] batchUpdate(SqlStatement template, List<Object[]> rows, int batchSize) {
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }
        int totalRows = rows.size();
        int chunkSize = batchSize > 0 ? batchSize : ExecutionOptions.DEFAULT_BATCH_SIZE;
        return withConnection(conn -> {
            int[] allCounts = new int[totalRows];
            int countOffset = 0;
            try (PreparedStatement ps = prepare(conn, template.sql(), false)) {
                int inChunk = 0;
                for (Object[] row : rows) {
                    bindRow(ps, row);
                    ps.addBatch();
                    inChunk++;
                    if (inChunk >= chunkSize) {
                        countOffset = flushChunk(ps, allCounts, countOffset, false, List.of());
                        inChunk = 0;
                    }
                }
                if (inChunk > 0) {
                    flushChunk(ps, allCounts, countOffset, false, List.of());
                }
                return allCounts;
            }
        });
    }

    private static int flushChunk(
            PreparedStatement ps,
            int[] allCounts,
            int countOffset,
            boolean wantKeys,
            List<Object> generatedKeys
    ) throws SQLException {
        int[] chunkCounts = ps.executeBatch();
        if (chunkCounts != null && chunkCounts.length > 0) {
            int copyLen = Math.min(chunkCounts.length, allCounts.length - countOffset);
            if (copyLen > 0) {
                System.arraycopy(chunkCounts, 0, allCounts, countOffset, copyLen);
                countOffset += copyLen;
            }
        }
        if (wantKeys) {
            try (ResultSet keys = ps.getGeneratedKeys()) {
                while (keys != null && keys.next()) {
                    generatedKeys.add(keys.getObject(1));
                }
            } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
                // 部分驱动在 executeBatch 后不支持 getGeneratedKeys，优雅降级不阻断主流程
            }
        }
        ps.clearBatch();
        return countOffset;
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
            bindRow(stmt, params);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(stmt);
            throw e;
        }
        return stmt;
    }

    private static void bindRow(PreparedStatement stmt, Object[] params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ParameterBinder.DEFAULT.bindValue(stmt, i + 1, params[i]);
        }
    }
}
