package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.executor.JdbcExecutor;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.executor.SingleRowRecordExtractor;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.DefaultChain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;

import javax.sql.DataSource;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 执行内核（等价于 JdbcTemplate，零业务语义）。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>只认 {@link SqlStatement} / {@link BatchSqlStatement}，不感知上层查询 DSL 或实体门面。</li>
 *   <li><b>拦截器链唯一入口</b>：所有 public 执行方法都先经 {@link SqlInterceptor} 链改写，无旁路。</li>
 *   <li>不感知事务：连接来源由 {@link ConnectionProvider} 决定；事务视图通过 {@link #bind(ConnectionProvider)} 派生，
 *       与原模板共享拦截器链与方言。</li>
 * </ul>
 */
public final class JoyaTemplate {

    private final DataSource dataSource;
    private final List<SqlInterceptor> interceptors;
    private final ConnectionProvider connections;
    private final DialectStrategy dialectStrategy;
    private final ExecutionOptions executionOptions;
    private final NamingPolicy namingPolicy;
    private final JdbcExecutor executor;

    public JoyaTemplate(DataSource dataSource) {
        this(dataSource, List.of(), 0);
    }

    public JoyaTemplate(DataSource dataSource, List<SqlInterceptor> interceptors) {
        this(dataSource, interceptors, 0);
    }

    public JoyaTemplate(DataSource dataSource, List<SqlInterceptor> interceptors, int queryTimeoutSeconds) {
        this(
                dataSource,
                interceptors,
                new DataSourceConnectionProvider(dataSource, queryTimeoutSeconds),
                DialectStrategy.cached(dataSource),
                ExecutionOptions.DEFAULT.withQueryTimeoutSeconds(queryTimeoutSeconds),
                NamingPolicy.DEFAULT
        );
    }

    public JoyaTemplate(
            DataSource dataSource,
            List<SqlInterceptor> interceptors,
            ConnectionProvider connections,
            DialectStrategy dialectStrategy,
            ExecutionOptions executionOptions,
            NamingPolicy namingPolicy
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.interceptors = sortInterceptors(interceptors);
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.dialectStrategy = dialectStrategy == null ? DialectStrategy.cached(dataSource) : dialectStrategy;
        this.executionOptions = executionOptions == null ? ExecutionOptions.DEFAULT : executionOptions;
        this.namingPolicy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
        this.executor = new JdbcExecutor(this.connections);
    }

    /**
     * 派生一个使用指定连接来源的模板视图（共享数据源标识、拦截器链、方言策略与配置）。
     * <p>事务视图即由此产生：{@code template.bind(txConnectionProvider)}。
     */
    public JoyaTemplate bind(ConnectionProvider provider) {
        return new JoyaTemplate(
                dataSource,
                interceptors,
                Objects.requireNonNull(provider, "provider must not be null"),
                dialectStrategy,
                executionOptions,
                namingPolicy
        );
    }

    private static List<SqlInterceptor> sortInterceptors(List<SqlInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            return List.of();
        }
        List<SqlInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(SqlInterceptor::order)
                .thenComparing(i -> i.getClass().getName()));
        return List.copyOf(sorted);
    }

    // ===== 拦截器链 =====

    /** 拦截器链统一改写入口；无拦截器时零开销（不触发方言探测）。 */
    private SqlStatement rewrite(SqlStatement statement) {
        if (interceptors.isEmpty()) {
            return statement;
        }
        SqlStatement withDbType = statement.metadata().dbType() == null
                ? statement.withDbType(dialect().dbType())
                : statement;
        return new DefaultChain(interceptors).proceed(new RewriteContext(withDbType, Map.of()));
    }

    /**
     * 批量语句的改写：拦截器只能改写 SQL 文本（如表名路由）。
     * 若拦截器试图追加参数（如租户条件），批量模式无法逐行补齐，必须响亮失败而非静默越权。
     */
    private String rewriteBatchSql(String sql, StatementMetadata metadata) {
        if (interceptors.isEmpty()) {
            return sql;
        }
        SqlStatement probe = SqlStatement.of(sql, List.of(), metadata);
        SqlStatement rewritten = rewrite(probe);
        if (!rewritten.parameters().isEmpty()) {
            throw new UnsupportedOperationException(
                    "SqlInterceptor added parameters to a batch statement; batch mode cannot bind them per row: " + sql);
        }
        return rewritten.sql();
    }

    // ===== 查询 =====

    /** 查询首行；无结果返回 {@code null}。 */
    public <T> T query(SqlStatement statement, RowMapper<T> mapper) {
        return executor.query(rewrite(statement), new SingleRowRecordExtractor<>(mapper));
    }

    /** 严格单行查询：0 行返回 {@code null}，1 行返回映射值，多于 1 行抛 {@link IllegalStateException}。 */
    public <T> T queryOne(SqlStatement statement, RowMapper<T> mapper) {
        SqlStatement rewritten = rewrite(statement);
        return executor.query(rewritten, rs -> {
            try {
                if (!rs.next()) {
                    return null;
                }
                T first = mapper.map(rs);
                if (rs.next()) {
                    throw new IllegalStateException("Expected at most 1 row, but query returned multiple rows: " + rewritten.sql());
                }
                return first;
            } catch (java.sql.SQLException e) {
                throw new com.sondertara.joya.exceptions.DbException(e);
            }
        });
    }

    public <T> T query(SqlStatement statement, ResultSetExtractor<T> extractor) {
        return executor.query(rewrite(statement), extractor);
    }

    public <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper) {
        return executor.queryList(rewrite(statement), mapper);
    }

    /** 流式查询：返回的 Stream 必须关闭（try-with-resources），关闭时才归还连接。 */
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper) {
        return executor.queryStream(rewrite(statement), mapper, dialect().streamFetchSize());
    }

    /** 方言分页截取：offset 起的 limit 行。 */
    public <T> List<T> queryLimit(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        SqlStatement rewritten = rewrite(statement);
        if (limit == 0) {
            return List.of();
        }
        return executor.queryList(pageStatement(rewritten, offset, limit), mapper);
    }

    /** 方言 count 包装。 */
    public long queryCount(SqlStatement statement) {
        return count(rewrite(statement));
    }

    public <T> PageSlice<T> queryPage(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        SqlStatement rewritten = rewrite(statement);
        long total = count(rewritten);
        List<T> rows = total == 0 || limit == 0 ? List.of() : executor.queryList(
                pageStatement(rewritten, offset, limit), mapper);
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
        SqlStatement rewrittenCount = rewrite(countStatement);
        Long counted = executor.query(rewrittenCount,
                new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class)));
        long total = counted == null ? 0L : counted;
        List<T> rows = total == 0 || limit == 0 ? List.of() : executor.queryList(
                pageStatement(rewrite(statement), offset, limit), mapper);
        return new PageSlice<>(rows, total);
    }

    /** 方言分页截取的流式查询：返回的 Stream 必须关闭（try-with-resources）。 */
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        validatePageBounds(offset, limit);
        SqlStatement rewritten = rewrite(statement);
        if (limit == 0) {
            return Stream.empty();
        }
        return executor.queryStream(
                pageStatement(rewritten, offset, limit),
                mapper,
                dialect().streamFetchSize());
    }

    private long count(SqlStatement rewritten) {
        SqlStatement countStmt = dialect().countSql(rewritten);
        Long total = executor.query(countStmt,
                new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class)));
        return total == null ? 0L : total;
    }

    private SqlStatement pageStatement(SqlStatement rewritten, long offset, long limit) {
        return dialect().pageSql(rewritten, offset, limit);
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
        return executor.update(rewrite(statement));
    }

    /** 插入并返回首个生成键；无生成键返回 {@code null}。 */
    public Object insert(SqlStatement statement) {
        return executor.insert(rewrite(statement));
    }

    /** 执行 INSERT 并返回真实受影响行数与首个生成键。 */
    public com.sondertara.joya.core.kernel.executor.Executor.InsertSlice insertExecute(SqlStatement statement) {
        return executor.insertExecute(rewrite(statement));
    }

    /** 列级绑定批量执行（SQL 文本同样经过拦截器链）。 */
    public int[] batchUpdate(BatchSqlStatement batch) {
        return batchExecute(batch).batchCounts();
    }

    /** 列级绑定批量执行（支持分片 Flush 与生成键收集，返回内核值类型）。 */
    public BatchSlice batchExecute(BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return BatchSlice.EMPTY;
        }
        String sql = rewriteBatchSql(batch.sql(), batch.metadata());
        BatchSqlStatement effective = batch.withSql(sql);
        return executor.batchExecute(effective);
    }

    /** 以 {@code template.sql()} 为模板，对每组参数各执行一次（SQL 文本同样经过拦截器链）。 */
    public int[] batchUpdate(SqlStatement template, List<Object[]> rows) {
        return batchUpdate(template, rows, executionOptions.batchSize());
    }

    /** 以 {@code template.sql()} 为模板并按指定分片大小批量执行。 */
    public int[] batchUpdate(SqlStatement template, List<Object[]> rows, int batchSize) {
        if (!template.parameters().isEmpty()) {
            throw new IllegalArgumentException("batch template must not carry parameters; pass them as rows");
        }
        String sql = rewriteBatchSql(template.sql(), template.metadata());
        return executor.batchUpdate(template.withSql(sql), rows, batchSize);
    }

    // ===== 逃生舱 =====

    /** 在一条连接上执行回调（不经过拦截器链，调用方自负其责）。 */
    public <T> T withConnection(ConnectionCallback<T> action) {
        return executor.withConnection(action);
    }

    /** 执行 SQL 脚本（DDL / 初始化数据，不经过拦截器链）。 */
    public void runScript(Reader reader) {
        executor.runScript(reader);
    }

    // ===== 访问器 =====

    /** 方言端口（由 {@link DialectStrategy} 决定解析与缓存策略）。 */
    public Dialect dialect() {
        return dialectStrategy.resolve(connections);
    }

    public DialectStrategy dialectStrategy() {
        return dialectStrategy;
    }

    public ExecutionOptions executionOptions() {
        return executionOptions;
    }

    public NamingPolicy namingPolicy() {
        return namingPolicy;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public List<SqlInterceptor> interceptors() {
        return interceptors;
    }

    public ConnectionProvider connections() {
        return connections;
    }
}
