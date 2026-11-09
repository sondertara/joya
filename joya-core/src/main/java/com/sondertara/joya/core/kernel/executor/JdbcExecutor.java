package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.support.LoggingOutputStream;
import com.sondertara.joya.core.kernel.support.ScriptRunner;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.ParameterBinder;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

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
    private final SqlExceptionTranslator exceptionTranslator;

    public JdbcExecutor(ConnectionProvider connections, SqlExceptionTranslator exceptionTranslator) {
        this.connections = Objects.requireNonNull(connections, "connectionProvider must not be null");
        this.exceptionTranslator = exceptionTranslator == null ? SqlExceptionTranslator.DEFAULT : exceptionTranslator;
    }

    // ===== 连接作用域 =====

    @Override
    public <T> T withConnection(ConnectionCallback<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        return withConnection(null, conn -> action.doWork(guardConnection(conn)));
    }

    private Connection guardConnection(Connection target) {
        boolean transactionControlForbidden = !connections.allowsTransactionControl();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name) || "abort".equals(name)) {
                        return null;
                    }
                    if (transactionControlForbidden && isTransactionControlMethod(name)) {
                        throw new UnsupportedOperationException(
                                "Cannot invoke " + name + "() directly on a Joya-managed transactional Connection; "
                                        + "use Tx APIs (commit / rollback / savepoint) instead");
                    }
                    if ("equals".equals(name)) {
                        Object other = args != null && args.length > 0 ? args[0] : null;
                        return proxy == other || target.equals(other);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "JoyaGuardedConnection[" + target + "]";
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    private static boolean isTransactionControlMethod(String methodName) {
        return switch (methodName) {
            case "commit", "rollback", "setAutoCommit",
                    "setSavepoint", "releaseSavepoint",
                    "setTransactionIsolation", "setReadOnly" -> true;
            default -> false;
        };
    }

    private <T> T withConnection(@Nullable SqlStatement statement, ConnectionCallback<T> action) {
        Connection conn = null;
        try {
            conn = connections.acquire();
            return action.doWork(conn);
        } catch (SQLException e) {
            throw exceptionTranslator.translate("jdbc", statement, e);
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
        return withConnection(statement, conn -> {
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
        int fetchSize = dbType != null ? dbType.defaultStreamFetchSize() : 2000;
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
            java.util.concurrent.atomic.AtomicReference<Runnable> cleanupRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Runnable cleanup = () -> {
                if (cleanedUp.compareAndSet(false, true)) {
                    closeStreamResources(
                            capturedRs,
                            capturedPs,
                            capturedConn,
                            streamAccess != null ? () -> streamAccess.onStreamClosed(cleanupRef.get()) : null);
                }
            };
            cleanupRef.set(cleanup);
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
            if (t instanceof SQLException sqlException) {
                throw exceptionTranslator.translate("queryStream", statement, sqlException);
            }
            throw new DbException(t);
        }
    }

    @Override
    public int update(SqlStatement statement) {
        return withConnection(statement, conn -> {
            try (PreparedStatement ps = prepare(conn, statement, false)) {
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public InsertSlice insertExecute(SqlStatement statement) {
        String pkColumn = statement.metadata() != null ? statement.metadata().primaryKey() : null;
        return withConnection(statement, conn -> {
            try (PreparedStatement ps = prepare(conn, statement, true)) {
                int affected = ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    Object key = keys != null && keys.next() ? extractGeneratedKey(keys, pkColumn) : null;
                    return new InsertSlice(affected, key);
                } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
                    logger.debug("Failed to read generated keys after insert; returning affected rows without key", ignored);
                    return new InsertSlice(affected, null);
                }
            }
        });
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
        String pkColumn = batch.metadata() != null ? batch.metadata().primaryKey() : null;

        SqlStatement probe = SqlStatement.of(batch.sql(), List.of(), batch.metadata());
        return withConnection(probe, conn -> {
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
                    List<ColumnBinding> extraColumns = batch.extraColumns();
                    List<Object> extraValues = batch.extraValues();
                    for (int e = 0; e < extraColumns.size(); e++) {
                        ColumnBinding column = extraColumns.get(e);
                        column.binder().bindValue(ps, columns.size() + e + 1, column.name(), extraValues.get(e));
                    }
                    ps.addBatch();
                    inChunk++;
                    if (inChunk >= chunkSize) {
                        countOffset = flushChunk(ps, allCounts, countOffset, inChunk, wantKeys, pkColumn, generatedKeys);
                        inChunk = 0;
                    }
                }
                if (inChunk > 0) {
                    flushChunk(ps, allCounts, countOffset, inChunk, wantKeys, pkColumn, generatedKeys);
                }
                return BatchSlice.of(allCounts, generatedKeys);
            }
        });
    }

    private static int flushChunk(
            PreparedStatement ps,
            int[] allCounts,
            int countOffset,
            int chunkRows,
            boolean wantKeys,
            @Nullable String pkColumn,
            List<Object> generatedKeys
    ) throws SQLException {
        int[] chunkCounts = ps.executeBatch();
        int available = Math.max(0, Math.min(chunkRows, allCounts.length - countOffset));
        int copyLen = chunkCounts == null ? 0 : Math.min(chunkCounts.length, available);
        if (copyLen > 0) {
            System.arraycopy(chunkCounts, 0, allCounts, countOffset, copyLen);
        }
        if (copyLen < available) {
            java.util.Arrays.fill(allCounts, countOffset + copyLen, countOffset + available, Statement.SUCCESS_NO_INFO);
        }
        countOffset += available;
        if (wantKeys) {
            try (ResultSet keys = ps.getGeneratedKeys()) {
                while (keys != null && keys.next()) {
                    generatedKeys.add(extractGeneratedKey(keys, pkColumn));
                }
            } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
                // 部分驱动在 executeBatch 后不支持 getGeneratedKeys，优雅降级不阻断主流程
            }
        }
        ps.clearBatch();
        return countOffset;
    }

    private static @Nullable Object extractGeneratedKey(ResultSet keys, @Nullable String pkColumn) throws SQLException {
        if (pkColumn != null && !pkColumn.isEmpty()) {
            try {
                ResultSetMetaData metaData = keys.getMetaData();
                if (metaData != null && metaData.getColumnCount() > 1) {
                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        String label = metaData.getColumnLabel(i);
                        String name = metaData.getColumnName(i);
                        if (pkColumn.equalsIgnoreCase(label) || pkColumn.equalsIgnoreCase(name)) {
                            return keys.getObject(i);
                        }
                    }
                }
            } catch (SQLException ignored) {
                // 元数据不可用时回退到第 1 列
            }
        }
        return keys.getObject(1);
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
            DbType dbType,
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
