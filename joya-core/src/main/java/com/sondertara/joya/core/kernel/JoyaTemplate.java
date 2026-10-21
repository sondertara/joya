package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.executor.JdbcExecutor;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleRowRecordExtractor;
import com.sondertara.joya.core.jdbc.support.dialect.BoundSql;
import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.interceptor.DefaultChain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;

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
 *   <li>只认 {@link SqlStatement} / {@link BatchSqlStatement}，不 import Entity / NativeSqlQuery。</li>
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
    private final JoyaSettings settings;
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
                JoyaSettings.DEFAULT.withQueryTimeoutSeconds(queryTimeoutSeconds)
        );
    }

    public JoyaTemplate(
            DataSource dataSource,
            List<SqlInterceptor> interceptors,
            ConnectionProvider connections,
            DialectStrategy dialectStrategy,
            JoyaSettings settings
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.interceptors = sortInterceptors(interceptors);
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.dialectStrategy = dialectStrategy == null ? DialectStrategy.cached(dataSource) : dialectStrategy;
        this.settings = settings == null ? JoyaSettings.DEFAULT : settings;
        this.executor = new JdbcExecutor(this.connections);
    }

    /**
     * 派生一个使用指定连接来源的模板视图（共享数据源标识、拦截器链、方言策略与客户端配置）。
     * <p>事务视图即由此产生：{@code template.bind(txConnectionProvider)}。
     */
    public JoyaTemplate bind(ConnectionProvider provider) {
        return new JoyaTemplate(
                dataSource,
                interceptors,
                Objects.requireNonNull(provider, "provider must not be null"),
                dialectStrategy,
                settings
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
        return executor.queryStream(rewrite(statement), mapper);
    }

    /** 方言分页截取：offset 起的 limit 行。 */
    public <T> List<T> queryLimit(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        SqlStatement rewritten = rewrite(statement);
        BoundSql page = dialect().pageSql(bound(rewritten), offset, limit);
        return executor.queryList(SqlStatement.select(page.getSqlString(), page.getArgs()), mapper);
    }

    /** 方言 count 包装。 */
    public long queryCount(SqlStatement statement) {
        return count(rewrite(statement));
    }

    public <T> PageResult<T> queryPage(SqlStatement statement, RowMapper<T> mapper, PageRequest page) {
        SqlStatement rewritten = rewrite(statement);
        long total = count(rewritten);
        List<T> rows = total == 0 ? List.of() : executor.queryList(
                pageStatement(rewritten, page.getOffset(), page.getSize()), mapper);
        return new PageResult<>(rows, total, page.getPage(), page.getSize());
    }

    /**
     * 分页查询（显式计数语句）：由渲染层直接提供不含 {@code ORDER BY / LIMIT} 的计数 SQL，避免方言再包一层子查询。
     *
     * @param statement      数据查询语句
     * @param countStatement 计数语句（单行单列）
     * @param mapper         行映射器
     * @param page           分页请求
     */
    public <T> PageResult<T> queryPage(SqlStatement statement, SqlStatement countStatement, RowMapper<T> mapper, PageRequest page) {
        SqlStatement rewrittenCount = rewrite(countStatement);
        Long counted = executor.query(rewrittenCount,
                new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class)));
        long total = counted == null ? 0L : counted;
        List<T> rows = total == 0 ? List.of() : executor.queryList(
                pageStatement(rewrite(statement), page.getOffset(), page.getSize()), mapper);
        return new PageResult<>(rows, total, page.getPage(), page.getSize());
    }

    /** 方言分页截取的流式查询：返回的 Stream 必须关闭（try-with-resources）。 */
    public <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, long offset, long limit) {
        return executor.queryStream(pageStatement(rewrite(statement), offset, limit), mapper);
    }

    private long count(SqlStatement rewritten) {
        BoundSql countSql = dialect().countSql(bound(rewritten));
        Long total = executor.query(SqlStatement.select(countSql.getSqlString(), countSql.getArgs()),
                new SingleRowRecordExtractor<>(new SingleColumnRowMapper<>(Long.class)));
        return total == null ? 0L : total;
    }

    private SqlStatement pageStatement(SqlStatement rewritten, long offset, long limit) {
        BoundSql page = dialect().pageSql(bound(rewritten), offset, limit);
        return SqlStatement.select(page.getSqlString(), page.getArgs());
    }

    private static BoundSql bound(SqlStatement statement) {
        return new BoundSql.BoundSqlObj(statement.sql(), statement.args().toArray());
    }

    // ===== 变更 =====

    public int update(SqlStatement statement) {
        return executor.update(rewrite(statement));
    }

    /** 插入并返回首个生成键；无生成键返回 {@code null}。 */
    public Object insert(SqlStatement statement) {
        return executor.insert(rewrite(statement));
    }

    /** 列级绑定批量执行（SQL 文本同样经过拦截器链）。 */
    public int[] batchUpdate(BatchSqlStatement batch) {
        if (batch.rowCount() == 0) {
            return new int[0];
        }
        String sql = rewriteBatchSql(batch.sql(), batch.metadata());
        BatchSqlStatement effective = sql.equals(batch.sql())
                ? batch
                : new BatchSqlStatement(sql, batch.columns(), batch.rows(), batch.metadata());
        return executor.batchUpdate(effective);
    }

    /** 以 {@code template.sql()} 为模板，对每组参数各执行一次（SQL 文本同样经过拦截器链）。 */
    public int[] batchUpdate(SqlStatement template, List<Object[]> rows) {
        if (!template.parameters().isEmpty()) {
            throw new IllegalArgumentException("batch template must not carry parameters; pass them as rows");
        }
        String sql = rewriteBatchSql(template.sql(), template.metadata());
        return executor.batchUpdate(template.withSql(sql), rows);
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

    public JoyaSettings settings() {
        return settings;
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
