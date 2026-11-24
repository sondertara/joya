package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.Executors;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.executor.SingleRowRecordExtractor;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.ExecutionObserver;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.BatchSlot;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.naming.NamingPolicy;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 内部 SQL 执行内核（零业务语义，协调准备、方言、观测与执行四阶段管线）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>接收结构化 {@link AstStatement} / {@link BatchAstStatement}（来自查询 DSL 与实体变更门面，单趟编译）
 *       以及原生 {@link SqlStatement} / {@link BatchSqlStatement}（来自 {@code SqlOps}）。</li>
 *   <li><b>SQL 执行唯一入口</b>：所有常规读写均经 {@link StatementPreparer} 与 {@link ExecutionObserver}，无旁路。</li>
 *   <li>不感知事务：连接来源由 {@link ConnectionProvider} 决定；事务视图通过 {@link #bind(ConnectionProvider)} 派生。</li>
 * </ul>
 */
public final class ExecutionKernel {

    private final Config config;
    private final StatementPreparer preparer;
    private final ExecutionObserver observer;
    private final ConnectionProvider connections;
    private final DialectStrategy dialectStrategy;
    private final ExecutionOptions executionOptions;
    private final NamingPolicy namingPolicy;
    private final RowMapperFactory rowMapperFactory;
    private final Executor executor;

    public record Config(
            List<SqlRewriter> rewriters,
            List<SqlExecutionListener> executionListeners,
            ConnectionProvider connections,
            DialectStrategy dialectStrategy,
            ExecutionOptions executionOptions,
            NamingPolicy namingPolicy,
            RowMapperFactory rowMapperFactory,
            SqlExceptionTranslator exceptionTranslator,
            Executor.Factory executorFactory,
            @Nullable String clientName
    ) {
        public Config(
                List<SqlRewriter> rewriters,
                List<SqlExecutionListener> executionListeners,
                ConnectionProvider connections,
                DialectStrategy dialectStrategy,
                ExecutionOptions executionOptions,
                NamingPolicy namingPolicy,
                RowMapperFactory rowMapperFactory,
                SqlExceptionTranslator exceptionTranslator,
                Executor.Factory executorFactory
        ) {
            this(rewriters, executionListeners, connections, dialectStrategy, executionOptions,
                    namingPolicy, rowMapperFactory, exceptionTranslator, executorFactory, null);
        }

        public Config {
            rewriters = rewriters == null ? List.of() : List.copyOf(rewriters);
            executionListeners = executionListeners == null ? List.of() : List.copyOf(executionListeners);
            connections = Objects.requireNonNull(connections, "connections must not be null");
            dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
            executionOptions = executionOptions == null ? ExecutionOptions.DEFAULT : executionOptions;
            if (executionOptions.queryTimeoutSeconds() > 0
                    && !connections.managesStreamLifecycle()) {
                connections = connections.withQueryTimeoutSeconds(executionOptions.queryTimeoutSeconds());
            }
            namingPolicy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
            rowMapperFactory = rowMapperFactory == null ? RowMapperFactory.DEFAULT : rowMapperFactory;
            exceptionTranslator = exceptionTranslator == null ? SqlExceptionTranslator.DEFAULT : exceptionTranslator;
            executorFactory = executorFactory == null ? Executors.JDBC : executorFactory;
            clientName = (clientName == null || clientName.isBlank()) ? null : clientName.trim();
        }
    }

    public ExecutionKernel(Config config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.connections = config.connections();
        this.dialectStrategy = config.dialectStrategy();
        this.executionOptions = config.executionOptions();
        this.namingPolicy = config.namingPolicy();
        this.rowMapperFactory = config.rowMapperFactory();
        this.preparer = new StatementPreparer(config.rewriters(), dialectStrategy, connections, namingPolicy);
        this.observer = new ExecutionObserver(config.executionListeners(), config.clientName());
        this.executor = Objects.requireNonNull(
                config.executorFactory().create(connections, config.exceptionTranslator()),
                "executorFactory must not return null");
    }

    public ExecutionKernel bind(ConnectionProvider provider) {
        return new ExecutionKernel(new Config(
                preparer.rewriters(),
                observer.listeners(),
                Objects.requireNonNull(provider, "provider must not be null"),
                dialectStrategy,
                executionOptions,
                namingPolicy,
                rowMapperFactory,
                config.exceptionTranslator(),
                config.executorFactory(),
                config.clientName()
        ));
    }

    public ExecutionKernel withSettings(ExecutionOptions newExecutionOptions, NamingPolicy newNamingPolicy) {
        ExecutionOptions effectiveOptions = newExecutionOptions == null ? executionOptions : newExecutionOptions;
        NamingPolicy effectiveNaming = newNamingPolicy == null ? namingPolicy : newNamingPolicy;
        ConnectionProvider updatedConnections = effectiveOptions.queryTimeoutSeconds() == executionOptions.queryTimeoutSeconds()
                ? connections
                : connections.withQueryTimeoutSeconds(effectiveOptions.queryTimeoutSeconds());
        return new ExecutionKernel(new Config(
                preparer.rewriters(),
                observer.listeners(),
                updatedConnections,
                dialectStrategy,
                effectiveOptions,
                effectiveNaming,
                rowMapperFactory,
                config.exceptionTranslator(),
                config.executorFactory(),
                config.clientName()
        ));
    }

    public <V> RowMapper<V> mapperFor(ResultType type, Class<V> javaType) {
        return rowMapperFactory.create(type, javaType);
    }

    public <V> RowMapper<V> mapperFor(ResultType type,
                                      Class<V> javaType,
                                      java.util.Map<String, com.sondertara.joya.utils.SqlDataUtils.ColumnReader> columnReaderOverrides) {
        if (columnReaderOverrides == null || columnReaderOverrides.isEmpty()) {
            return rowMapperFactory.create(type, javaType);
        }
        return rowMapperFactory.create(type, javaType, columnReaderOverrides);
    }

    // ===== 查询（AST 单趟编译入口 + 原生 SQL 入口） =====

    public <T> T query(AstStatement statement, RowMapper<T> mapper) {
        return executeQuery(preparer.prepare(statement).statement(), mapper);
    }

    public <T> T query(SqlStatement statement, RowMapper<T> mapper) {
        return executeQuery(preparer.prepare(statement).statement(), mapper);
    }

    public <T> T queryOne(AstStatement statement, RowMapper<T> mapper) {
        return executeQueryOne(preparer.prepare(statement).statement(), mapper);
    }

    public <T> T queryOne(SqlStatement statement, RowMapper<T> mapper) {
        return executeQueryOne(preparer.prepare(statement).statement(), mapper);
    }

    public <T> T query(AstStatement statement, ResultSetExtractor<T> extractor) {
        SqlStatement rewritten = preparer.prepare(statement).statement();
        return observer.observe(rewritten, () -> executor.query(rewritten, extractor),
                result -> SqlExecutionResult.rows(-1L));
    }

    public <T> T query(SqlStatement statement, ResultSetExtractor<T> extractor) {
        SqlStatement rewritten = preparer.prepare(statement).statement();
        return observer.observe(rewritten, () -> executor.query(rewritten, extractor),
                result -> SqlExecutionResult.rows(-1L));
    }

    public <T> List<T> queryList(AstStatement statement, RowMapper<T> mapper) {
        return executeQueryList(preparer.prepare(statement).statement(), mapper);
    }

    public <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper) {
        return executeQueryList(preparer.prepare(statement).statement(), mapper);
    }

    public <T> Stream<T> queryStream(AstStatement statement, RowMapper<T> mapper) {
        return executeQueryStream(preparer.prepare(statement), mapper);
    }

    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper) {
        return executeQueryStream(preparer.prepare(statement), mapper);
    }

    public <T> List<T> queryLimit(AstStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeQueryLimit(preparer.prepare(statement), mapper, offset, limit);
    }

    public <T> List<T> queryLimit(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeQueryLimit(preparer.prepare(statement), mapper, offset, limit);
    }

    public <T> PageSlice<T> queryPage(AstStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeImplicitPage(preparer.prepare(statement), mapper, offset, limit);
    }

    public <T> PageSlice<T> queryPage(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeImplicitPage(preparer.prepare(statement), mapper, offset, limit);
    }

    public <T> PageSlice<T> queryPage(AstStatement statement, AstStatement countStatement,
                                      RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        SqlStatement rewrittenCount = preparer.prepare(countStatement).statement();
        long total = executeCount(rewrittenCount, offset, limit);
        if (total == 0 || limit == 0) {
            return new PageSlice<>(List.of(), total);
        }
        return executeExplicitPagedData(preparer.prepare(statement), mapper, offset, limit, total);
    }

    public <T> PageSlice<T> queryPage(SqlStatement statement, SqlStatement countStatement,
                                      RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        SqlStatement rewrittenCount = preparer.prepare(countStatement).statement();
        long total = executeCount(rewrittenCount, offset, limit);
        if (total == 0 || limit == 0) {
            return new PageSlice<>(List.of(), total);
        }
        return executeExplicitPagedData(preparer.prepare(statement), mapper, offset, limit, total);
    }

    public <T> Stream<T> queryStream(AstStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeStreamLimit(preparer.prepare(statement), mapper, offset, limit);
    }

    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        return executeStreamLimit(preparer.prepare(statement), mapper, offset, limit);
    }

    private <T> T executeQuery(SqlStatement rewritten, RowMapper<T> mapper) {
        return observer.observe(rewritten,
                () -> executor.query(rewritten, new SingleRowRecordExtractor<>(mapper)),
                result -> SqlExecutionResult.rows(result == null ? 0L : 1L));
    }

    private <T> T executeQueryOne(SqlStatement rewritten, RowMapper<T> mapper) {
        return observer.observe(rewritten, () -> executor.query(rewritten, rs -> {
            if (!rs.next()) {
                return null;
            }
            T first = mapper.forResultSet(rs).map(rs);
            if (rs.next()) {
                throw new IllegalStateException(
                        "Expected at most 1 row, but query returned multiple rows: " + rewritten.sql());
            }
            return first;
        }), result -> SqlExecutionResult.rows(result == null ? 0L : 1L));
    }

    private <T> List<T> executeQueryList(SqlStatement rewritten, RowMapper<T> mapper) {
        return observer.observe(rewritten, () -> executor.queryList(rewritten, mapper),
                result -> SqlExecutionResult.rows(result.size()));
    }

    private <T> Stream<T> executeQueryStream(StatementPreparer.Prepared prepared, RowMapper<T> mapper) {
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        return observer.observeStream(rewritten, null,
                () -> executor.queryStream(rewritten, mapper, currentDialect.streamFetchSize()),
                connections::onStreamClose);
    }

    private <T> List<T> executeQueryLimit(StatementPreparer.Prepared prepared, RowMapper<T> mapper,
                                          long offset, long limit) {
        if (limit == 0) {
            return List.of();
        }
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        SqlStatement paged = currentDialect.pageSql(rewritten, offset, limit);
        return observer.observe(paged, pageView(rewritten, offset, limit),
                () -> executor.queryList(paged, mapper),
                result -> SqlExecutionResult.rows(result.size()));
    }

    private <T> PageSlice<T> executeImplicitPage(StatementPreparer.Prepared prepared, RowMapper<T> mapper,
                                                 long offset, long limit) {
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        SqlStatement countStmt = currentDialect.countSql(rewritten);
        SqlExecutionContext.PaginationView view = pageView(rewritten, offset, limit);
        Long counted = observer.observe(countStmt, view,
                () -> executor.query(countStmt,
                        new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class))),
                value -> SqlExecutionResult.rows(value == null ? 0L : 1L));
        long total = counted == null ? 0L : counted;
        if (total == 0 || limit == 0) {
            return new PageSlice<>(List.of(), total);
        }
        SqlStatement paged = currentDialect.pageSql(rewritten, offset, limit);
        List<T> rows = observer.observe(paged, view,
                () -> executor.queryList(paged, mapper),
                result -> SqlExecutionResult.rows(result.size()));
        return new PageSlice<>(rows, total);
    }

    private long executeCount(SqlStatement rewrittenCount, long offset, long limit) {
        Long counted = observer.observe(rewrittenCount,
                pageView(rewrittenCount, offset, limit),
                () -> executor.query(rewrittenCount,
                        new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class))),
                value -> SqlExecutionResult.rows(value == null ? 0L : 1L));
        return counted == null ? 0L : counted;
    }

    private <T> PageSlice<T> executeExplicitPagedData(StatementPreparer.Prepared preparedData,
                                                      RowMapper<T> mapper,
                                                      long offset, long limit, long total) {
        SqlStatement rewrittenData = preparedData.statement();
        Dialect currentDialect = dialectFor(preparedData);
        SqlStatement pagedData = currentDialect.pageSql(rewrittenData, offset, limit);
        List<T> rows = observer.observe(pagedData,
                pageView(rewrittenData, offset, limit),
                () -> executor.queryList(pagedData, mapper),
                result -> SqlExecutionResult.rows(result.size()));
        return new PageSlice<>(rows, total);
    }

    private <T> Stream<T> executeStreamLimit(StatementPreparer.Prepared prepared, RowMapper<T> mapper,
                                             long offset, long limit) {
        if (limit == 0) {
            return Stream.empty();
        }
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        SqlStatement paged = currentDialect.pageSql(rewritten, offset, limit);
        return observer.observeStream(paged, pageView(rewritten, offset, limit),
                () -> executor.queryStream(paged, mapper, currentDialect.streamFetchSize()),
                connections::onStreamClose);
    }

    private SqlExecutionContext.@Nullable PaginationView pageView(SqlStatement logicalStatement, long offset, long limit) {
        return observer.hasListeners()
                ? new SqlExecutionContext.PaginationView(logicalStatement, offset, limit)
                : null;
    }

    private Dialect dialectFor(StatementPreparer.Prepared prepared) {
        SqlStatement rewritten = prepared.statement();
        var dbType = rewritten.metadata() != null ? rewritten.metadata().dbType() : null;
        Dialect candidate = prepared.dialect() != null ? prepared.dialect() : peekDialect();
        if (candidate == null) {
            candidate = dialect();
        }
        if (dbType == null || candidate.dbType() == dbType) {
            return candidate;
        }
        return Dialect.of(dbType);
    }

    private static void validatePageBounds(long offset, long limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
    }

    // ===== 变更（AST 单趟编译入口 + 原生 SQL 入口） =====

    public int update(AstStatement statement) {
        return executeUpdate(preparer.prepare(statement).statement());
    }

    public int update(SqlStatement statement) {
        return executeUpdate(preparer.prepare(statement).statement());
    }

    public Executor.InsertSlice insertExecute(AstStatement statement) {
        return executeInsert(preparer.prepare(statement).statement());
    }

    public Executor.InsertSlice insertExecute(SqlStatement statement) {
        return executeInsert(preparer.prepare(statement).statement());
    }

    public BatchSlice batchExecute(BatchAstStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        return executePreparedBatch(preparer.prepareBatch(batch), false);
    }

    public BatchSlice batchExecute(BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        return executePreparedBatch(preparer.prepareBatch(batch), false);
    }

    /** 精确行计数批量执行（AST 入口）：单趟编译并经改写器与监听器，但底层逐行返回真实受影响行数。 */
    public BatchSlice batchExecuteExact(BatchAstStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        return executePreparedBatch(preparer.prepareBatch(batch), true);
    }

    private int executeUpdate(SqlStatement rewritten) {
        return observer.observe(rewritten, () -> executor.update(rewritten),
                affected -> SqlExecutionResult.affected((long) affected));
    }

    private Executor.InsertSlice executeInsert(SqlStatement rewritten) {
        return observer.observe(rewritten, () -> executor.insertExecute(rewritten),
                slice -> SqlExecutionResult.insert(slice.affectedRows(), slice.generatedKey()));
    }

    private BatchSlice executePreparedBatch(BatchSqlStatement effective, boolean exact) {
        int rowCount = effective.rowCount();
        SqlStatement probe = SqlStatement.of(effective.sql(), probeParameters(effective), effective.metadata());
        return observer.observeBatch(probe, rowCount,
                () -> exact ? executor.batchExecuteExact(effective) : executor.batchExecute(effective),
                slice -> new SqlExecutionResult(-1L, -1L, slice.batchCounts(), slice.generatedKeys(), 0L));
    }

    private static List<SqlParameter> probeParameters(BatchSqlStatement batch) {
        List<BatchSlot> slots = batch.slots();
        List<SqlParameter> parameters = new ArrayList<>(slots.size());
        Object[] firstRow = batch.rows().isEmpty() ? null : batch.rows().get(0);
        for (int i = 0; i < slots.size(); i++) {
            BatchSlot slot = slots.get(i);
            parameters.add(SqlParameter.of(
                    i + 1,
                    slot.name(),
                    slot.resolveValue(firstRow),
                    slot.source(),
                    slot.binder()));
        }
        return parameters;
    }

    // ===== 逃生舱 =====

    public <T> T withConnection(ConnectionCallback<T> action) {
        return executor.withConnection(action);
    }

    public void runScript(Reader reader) {
        Dialect cached = peekDialect();
        executor.runScript(reader, cached != null ? cached.dbType() : null);
    }

    // ===== 访问器 =====

    public Dialect dialect() {
        return dialectStrategy.resolve(connections);
    }

    public @Nullable Dialect peekDialect() {
        return dialectStrategy.peek();
    }

    public ExecutionOptions executionOptions() {
        return executionOptions;
    }

    public NamingPolicy namingPolicy() {
        return namingPolicy;
    }

    public boolean hasRewriters() {
        return preparer.hasRewriters();
    }

    public boolean hasApplicableRewriters(Collection<Operation> operations,
                                          @Nullable Class<?> entityType,
                                          Set<TableRef> tables,
                                          @Nullable DbType dbType) {
        return preparer.hasApplicableRewriters(operations, entityType, tables, dbType);
    }

    public ConnectionProvider connections() {
        return connections;
    }

    public @Nullable String clientName() {
        return config.clientName();
    }
}
