package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.executor.ListRecordExtractor;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.RowMapper;
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
        var dbType = statement.metadata().dbType();
        int fetchSize = dbType != null && dbType.isLikeMysql() ? Integer.MIN_VALUE : 2000;
        return queryStream(statement, mapper, fetchSize);
    }

    @Override
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, int fetchSize) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = connections.acquire();
            ps = prepare(conn, statement, false);
            applyStreamFetchSize(ps, fetchSize);
            rs = ps.executeQuery();
            final Connection capturedConn = conn;
            final PreparedStatement capturedPs = ps;
            final ResultSet capturedRs = rs;
            ResultSetSpliterator.StreamAccess streamAccess = connections instanceof ResultSetSpliterator.StreamAccess access
                    ? access
                    : null;
            java.util.concurrent.atomic.AtomicBoolean cleanedUp = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable cleanup = () -> {
                if (cleanedUp.compareAndSet(false, true)) {
                    closeStreamResources(
                            capturedRs,
                            capturedPs,
                            capturedConn,
                            streamAccess != null ? streamAccess::onStreamClosed : null);
                }
            };
            // 流关闭时才归还连接；事务边界也可通过 StreamAccess 对称清理同一组资源。
            Stream<T> stream = new ResultSetSpliterator<>(capturedRs, mapper, streamAccess)
                    .stream()
                    .onClose(cleanup);
            if (streamAccess != null) {
                streamAccess.registerStream(cleanup);
            }
            return stream;
        } catch (Throwable t) {
            closeStreamResourcesOnError(rs, ps, conn, t);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new DbException(t);
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
        return insertExecute(statement).generatedKey();
    }

    @Override
    public InsertSlice insertExecute(SqlStatement statement) {
        return withConnection(conn -> {
            try (PreparedStatement ps = prepare(conn, statement, true)) {
                int affected = ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    Object key = keys != null && keys.next() ? keys.getObject(1) : null;
                    return new InsertSlice(affected, key);
                } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
                    return new InsertSlice(affected, null);
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

            var dbType = batch.metadata() != null ? batch.metadata().dbType() : null;
            try (PreparedStatement ps = prepare(conn, batch.sql(), dbType, wantKeys)) {
                int inChunk = 0;
                for (int r = 0; r < totalRows; r++) {
                    Object[] row = rows.get(r);
                    if (row == null || row.length != columns.size()) {
                        throw new IllegalArgumentException("Batch row at index " + r
                                + " has invalid parameter length (expected " + columns.size() + ")");
                    }
                    ps.clearParameters();
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
        var dbType = template.metadata() != null ? template.metadata().dbType() : null;
        int expectedParams = SqlFormatUtils.countPlaceholders(template.sql(), dbType);
        return withConnection(conn -> {
            int[] allCounts = new int[totalRows];
            int countOffset = 0;
            try (PreparedStatement ps = prepare(conn, template.sql(), dbType, false)) {
                int inChunk = 0;
                for (int r = 0; r < totalRows; r++) {
                    Object[] row = rows.get(r);
                    if (row == null) {
                        throw new IllegalArgumentException("Batch row at index " + r + " must not be null");
                    }
                    if (expectedParams > 0 && row.length != expectedParams) {
                        throw new IllegalArgumentException("Batch row at index " + r + " has " + row.length
                                + " parameters, but SQL expects " + expectedParams + ": " + template.sql());
                    }
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
            try (PrintWriter logWriter = new PrintWriter(new LoggingOutputStream(logger, Level.DEBUG));
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
        var dbType = statement.metadata() != null ? statement.metadata().dbType() : null;
        String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(statement.sql(), dbType);
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

    private PreparedStatement prepare(
            Connection conn,
            String sql,
            com.sondertara.joya.core.kernel.dialect.DbType dbType,
            boolean generatedKeys,
            Object... params
    ) throws SQLException {
        String jdbcStyleSql = SqlFormatUtils.getJdbcStyleSql(sql, dbType);
        PreparedStatement ps = generatedKeys
                ? conn.prepareStatement(jdbcStyleSql, Statement.RETURN_GENERATED_KEYS)
                : conn.prepareStatement(jdbcStyleSql);
        try {
            bindRow(ps, params);
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

    private static void applyStreamFetchSize(PreparedStatement ps, int fetchSize) throws SQLException {
        try {
            ps.setFetchSize(fetchSize);
        } catch (SQLException e) {
            if (fetchSize < 0) {
                ps.setFetchSize(2000);
            } else {
                throw e;
            }
        }
    }

    private void closeStreamResources(ResultSet rs, PreparedStatement ps, Connection conn, Runnable onClosed) {
        Throwable primary = null;
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (Throwable t) {
            primary = t;
        }
        try {
            if (ps != null) {
                ps.close();
            }
        } catch (Throwable t) {
            if (primary != null) {
                primary.addSuppressed(t);
            } else {
                primary = t;
            }
        }
        try {
            if (conn != null) {
                connections.release(conn);
            }
        } catch (Throwable t) {
            if (primary != null) {
                primary.addSuppressed(t);
            } else {
                primary = t;
            }
        } finally {
            if (onClosed != null) {
                onClosed.run();
            }
        }
        if (primary instanceof RuntimeException re) {
            throw re;
        }
        if (primary instanceof Error err) {
            throw err;
        }
        if (primary != null) {
            logger.warn("Failed to close stream resources", primary);
        }
    }

    private void closeStreamResourcesOnError(ResultSet rs, PreparedStatement ps, Connection conn, Throwable primary) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Throwable t) {
                primary.addSuppressed(t);
            }
        }
        if (ps != null) {
            try {
                ps.close();
            } catch (Throwable t) {
                primary.addSuppressed(t);
            }
        }
        if (conn != null) {
            try {
                connections.release(conn);
            } catch (Throwable t) {
                primary.addSuppressed(t);
            }
        }
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
        stmt.clearParameters();
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ParameterBinder.DEFAULT.bindValue(stmt, i + 1, params[i]);
        }
    }
}
