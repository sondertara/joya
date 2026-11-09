package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.Executors;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.ResultSetSpliterator;
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
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 内部 SQL 执行内核（零业务语义，协调准备、方言、观测与执行四阶段管线）。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>只认 {@link SqlStatement} / {@link BatchSqlStatement}，不感知上层查询 DSL 或实体门面。</li>
 *   <li><b>SQL 执行唯一入口</b>：所有 public 执行方法都先经 {@link StatementPreparer}
 *       （AST 结构改写 {@link SqlRewriter} 后由 {@link SqlRenderer} 单一渲染），无旁路
 *       （注：单参隐式计数分页 {@link #queryPage(SqlStatement, RowMapper, long, long)} 先对基础语句执行一次
 *       {@code prepare}，再由方言派生 {@code countSql} / {@code pageSql} 两条物理 SQL，避免重复改写）；
 *       执行由 {@link Executor} 端口完成，观测由 {@link ExecutionObserver} 包装。</li>
 *   <li>不感知事务：连接来源由 {@link ConnectionProvider} 决定；事务视图通过 {@link #bind(ConnectionProvider)} 派生，
 *       与原内核共享改写器链与方言。</li>
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

    /**
     * 内核装配配置；所有字段在构造时校验一次。
     *
     * @param connections     唯一连接来源（方言探测、执行、逃生舱都经此端口）
     * @param executorFactory 执行器工厂（可选，默认 {@link Executors#JDBC}）
     */
    public record Config(
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
        public Config {
            rewriters = rewriters == null ? List.of() : List.copyOf(rewriters);
            executionListeners = executionListeners == null ? List.of() : List.copyOf(executionListeners);
            connections = Objects.requireNonNull(connections, "connections must not be null");
            dialectStrategy = Objects.requireNonNull(dialectStrategy, "dialectStrategy must not be null");
            executionOptions = executionOptions == null ? ExecutionOptions.DEFAULT : executionOptions;
            if (executionOptions.queryTimeoutSeconds() > 0
                    && !(connections instanceof ResultSetSpliterator.StreamAccess)
                    && !(connections instanceof DataSourceConnectionProvider)) {
                connections = ConnectionProvider.withFallbackQueryTimeout(
                        connections, executionOptions.queryTimeoutSeconds());
            }
            namingPolicy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
            rowMapperFactory = rowMapperFactory == null ? RowMapperFactory.DEFAULT : rowMapperFactory;
            exceptionTranslator = exceptionTranslator == null ? SqlExceptionTranslator.DEFAULT : exceptionTranslator;
            executorFactory = executorFactory == null ? Executors.JDBC : executorFactory;
        }
    }

    public ExecutionKernel(Config config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.connections = config.connections();
        this.dialectStrategy = config.dialectStrategy();
        this.executionOptions = config.executionOptions();
        this.namingPolicy = config.namingPolicy();
        this.rowMapperFactory = config.rowMapperFactory();
        this.preparer = new StatementPreparer(config.rewriters(), dialectStrategy, connections);
        this.observer = new ExecutionObserver(config.executionListeners());
        this.executor = Objects.requireNonNull(
                config.executorFactory().create(connections, config.exceptionTranslator()),
                "executorFactory must not return null");
    }

    /**
     * 派生一个使用指定连接来源的内核视图（共享改写器链、方言策略与配置）。
     * <p>事务视图即由此产生：{@code kernel.bind(txConnectionProvider)}；执行器由工厂用新连接来源重建。
     */
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
                config.executorFactory()
        ));
    }

    /**
     * 按结果类型解析行映射器；映射实现由上层注入，kernel 不依赖具体 JDBC 映射器。
     */
    public <V> RowMapper<V> mapperFor(ResultType type, Class<V> javaType) {
        return rowMapperFactory.create(type, javaType);
    }

    /**
     * 构造单次 SQL 执行的只读上下文属性包（属性仅由内核预置，如批处理的 {@code batch} / {@code batchRows}）。
     */
    private Map<String, Object> newExecutionAttributes() {
        return Map.of();
    }

    // ===== 查询 =====

    /** 查询首行；无结果返回 {@code null}。 */
    public <T> T query(SqlStatement statement, RowMapper<T> mapper) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs,
                () -> executor.query(rewritten, new SingleRowRecordExtractor<>(mapper)),
                result -> SqlExecutionResult.rows(result == null ? 0L : 1L));
    }

    /** 严格单行查询：0 行返回 {@code null}，1 行返回映射值，多于 1 行抛 {@link IllegalStateException}。 */
    public <T> T queryOne(SqlStatement statement, RowMapper<T> mapper) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs, () -> executor.query(rewritten, rs -> {
            try {
                if (!rs.next()) {
                    return null;
                }
                T first = mapper.map(rs);
                if (rs.next()) {
                    throw new IllegalStateException("Expected at most 1 row, but query returned multiple rows: " + rewritten.sql());
                }
                return first;
            } catch (SQLException e) {
                throw new DbException(e);
            }
        }), result -> SqlExecutionResult.rows(result == null ? 0L : 1L));
    }

    public <T> T query(SqlStatement statement, ResultSetExtractor<T> extractor) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs, () -> executor.query(rewritten, extractor),
                result -> SqlExecutionResult.rows(-1L));
    }

    public <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs, () -> executor.queryList(rewritten, mapper),
                result -> SqlExecutionResult.rows(result.size()));
    }

    /** 流式查询：返回的 Stream 必须关闭（try-with-resources），关闭时才归还连接。 */
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper) {
        Map<String, Object> attrs = newExecutionAttributes();
        StatementPreparer.Prepared prepared = preparer.prepare(statement, attrs);
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        return observer.observeStream(rewritten, attrs,
                () -> executor.queryStream(rewritten, mapper, currentDialect.streamFetchSize()));
    }

    /** 方言分页截取：offset 起的 limit 行。 */
    public <T> List<T> queryLimit(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        Map<String, Object> attrs = newExecutionAttributes();
        StatementPreparer.Prepared prepared = preparer.prepare(statement, attrs);
        if (limit == 0) {
            return List.of();
        }
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        return observer.observe(rewritten, false, attrs,
                () -> executor.queryList(currentDialect.pageSql(rewritten, offset, limit), mapper),
                result -> SqlExecutionResult.rows(result.size()));
    }

    /**
     * 隐式计数分页查询（主要用于原生 SQL）：原始 {@code statement} 先经过一次 {@code StatementPreparer.prepare}
     * （完成 AST 改写），再由 {@link Dialect#countSql(SqlStatement)} 与 {@link Dialect#pageSql(SqlStatement, long, long)}
     * 分别派生计数包装语句与分页语句（不重复触发改写器），两次物理执行各自独立进入 {@link ExecutionObserver}。
     */
    public <T> PageSlice<T> queryPage(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        Map<String, Object> attrs = newExecutionAttributes();
        StatementPreparer.Prepared prepared = preparer.prepare(statement, attrs);
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        SqlStatement countStmt = currentDialect.countSql(rewritten);
        Long counted = observer.observe(countStmt, false, attrs,
                () -> executor.query(countStmt,
                        new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class))),
                value -> SqlExecutionResult.rows(value == null ? 0L : 1L));
        long total = counted == null ? 0L : counted;
        if (total == 0 || limit == 0) {
            return new PageSlice<>(List.of(), total);
        }
        List<T> rows = observer.observe(rewritten, false, attrs,
                () -> executor.queryList(currentDialect.pageSql(rewritten, offset, limit), mapper),
                result -> SqlExecutionResult.rows(result.size()));
        return new PageSlice<>(rows, total);
    }

    /**
     * 分页查询（显式计数语句）：由渲染层直接提供不含 {@code ORDER BY / LIMIT} 的计数 SQL，避免方言再包一层子查询。
     *
     * @param statement      数据查询语句
     * @param countStatement 计数语句（单行单列）
     * @param mapper         行映射器
     * @param offset         起始偏移量
     * @param limit          每页条数
     */
    public <T> PageSlice<T> queryPage(SqlStatement statement, SqlStatement countStatement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        Map<String, Object> countAttrs = newExecutionAttributes();
        SqlStatement rewrittenCount = preparer.prepare(countStatement, countAttrs).statement();
        Long counted = observer.observe(rewrittenCount, false, countAttrs,
                () -> executor.query(rewrittenCount,
                        new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class))),
                value -> SqlExecutionResult.rows(value == null ? 0L : 1L));
        long total = counted == null ? 0L : counted;
        if (total == 0 || limit == 0) {
            return new PageSlice<>(List.of(), total);
        }
        // 数据查询同样被观测（与计数语句各触发一次监听），且仅在有数据时才准备 / 拦截
        Map<String, Object> dataAttrs = newExecutionAttributes();
        StatementPreparer.Prepared preparedData = preparer.prepare(statement, dataAttrs);
        SqlStatement rewrittenData = preparedData.statement();
        Dialect currentDialect = dialectFor(preparedData);
        List<T> rows = observer.observe(rewrittenData, false, dataAttrs,
                () -> executor.queryList(currentDialect.pageSql(rewrittenData, offset, limit), mapper),
                result -> SqlExecutionResult.rows(result.size()));
        return new PageSlice<>(rows, total);
    }

    /** 方言分页截取的流式查询：返回的 Stream 必须关闭（try-with-resources）。 */
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        Map<String, Object> attrs = newExecutionAttributes();
        StatementPreparer.Prepared prepared = preparer.prepare(statement, attrs);
        if (limit == 0) {
            return Stream.empty();
        }
        SqlStatement rewritten = prepared.statement();
        Dialect currentDialect = dialectFor(prepared);
        return observer.observeStream(rewritten, attrs,
                () -> executor.queryStream(currentDialect.pageSql(rewritten, offset, limit), mapper,
                        currentDialect.streamFetchSize()));
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

    // ===== 变更 =====

    public int update(SqlStatement statement) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs, () -> executor.update(rewritten),
                affected -> SqlExecutionResult.affected((long) affected));
    }

    /** 执行 INSERT 并返回真实受影响行数与首个生成键。 */
    public Executor.InsertSlice insertExecute(SqlStatement statement) {
        Map<String, Object> attrs = newExecutionAttributes();
        SqlStatement rewritten = preparer.prepare(statement, attrs).statement();
        return observer.observe(rewritten, false, attrs, () -> executor.insertExecute(rewritten),
                slice -> SqlExecutionResult.insert(slice.affectedRows(), slice.generatedKey()));
    }

    /** 列级绑定批量执行（支持分片 Flush 与生成键收集，返回内核值类型）；与单条语句一样经过完整改写与拦截链。 */
    public BatchSlice batchExecute(BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        Map<String, Object> attrs = new java.util.HashMap<>(4);
        attrs.put(SqlExecutionContext.ATTR_BATCH, Boolean.TRUE);
        attrs.put(SqlExecutionContext.ATTR_BATCH_ROWS, batch.rowCount());
        BatchSqlStatement effective = preparer.prepareBatch(batch, attrs);
        SqlStatement probe = SqlStatement.of(effective.sql(), probeParameters(effective), effective.metadata());
        return observer.observe(probe, true, attrs, () -> executor.batchExecute(effective),
                slice -> new SqlExecutionResult(-1L, -1L, slice.batchCounts(), slice.generatedKeys(), 0L));
    }

    /**
     * 批处理观测视图：用首行值构造与占位符一一对应的参数列表，保证监听器拿到的是自洽的
     * {@link SqlStatement}（占位符数 == 参数数），与单条路径的参数契约一致。
     */
    private static List<SqlParameter> probeParameters(BatchSqlStatement batch) {
        List<SqlParameter> parameters = new ArrayList<>(
                batch.columns().size() + batch.extraColumns().size());
        Object[] firstRow = batch.rows().isEmpty() ? null : batch.rows().get(0);
        for (int i = 0; i < batch.columns().size(); i++) {
            Object value = firstRow != null && i < firstRow.length ? firstRow[i] : null;
            parameters.add(SqlParameter.of(i + 1, batch.columns().get(i).name(), value, ParameterSource.RAW));
        }
        int offset = batch.columns().size();
        for (int i = 0; i < batch.extraColumns().size(); i++) {
            parameters.add(SqlParameter.of(offset + i + 1, batch.extraColumns().get(i).name(),
                    batch.extraValues().get(i), ParameterSource.RAW));
        }
        return parameters;
    }

    // ===== 逃生舱 =====

    /** 在一条连接上执行回调（不经过改写器链，调用方自负其责）。 */
    public <T> T withConnection(ConnectionCallback<T> action) {
        return executor.withConnection(action);
    }

    /** 执行 SQL 脚本（DDL / 初始化数据，不经过改写器链）。 */
    public void runScript(Reader reader) {
        executor.runScript(reader);
    }

    // ===== 访问器 =====

    /** 方言端口（由 {@link DialectStrategy} 决定解析与缓存策略）。 */
    public Dialect dialect() {
        return dialectStrategy.resolve(connections);
    }

    /** 仅返回已缓存或固定配置的方言（不打开数据库连接探测）。 */
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

    public ConnectionProvider connections() {
        return connections;
    }
}
