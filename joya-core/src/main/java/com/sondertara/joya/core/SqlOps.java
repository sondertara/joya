package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.PageSlice;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.result.MutationResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 原生参数化 SQL 链式操作门面 —— 由 {@link DataOps#sql(String, Object...)} 创建。
 *
 * <p>统一承载原生 SQL 的读（{@code list/one/first/value/page/stream/extract}）与写（{@code update/insert/batch}）收尾动作，
 * 所有执行均经过 {@link ExecutionKernel} 及其改写/观测管线。
 */
public final class SqlOps {

    private final ExecutionKernel kernel;
    private final String sql;
    private final Object[] args;
    private final SqlStatement presetStatement;
    private DbType explicitDbType;
    private DbType resolvedDbType;

    public SqlOps(ExecutionKernel kernel, String sql, Object... args) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.sql = Objects.requireNonNull(sql, "sql must not be null");
        this.args = args == null ? new Object[0] : args.clone();
        this.presetStatement = null;
    }

    public SqlOps(ExecutionKernel kernel, SqlStatement statement) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.presetStatement = Objects.requireNonNull(statement, "statement must not be null");
        this.sql = statement.sql();
        this.args = statement.args().toArray();
    }

    /** 显式指定当前原生 SQL 的目标数据库方言类型（可选）。 */
    public SqlOps dbType(DbType dbType) {
        this.explicitDbType = dbType;
        return this;
    }

    private SqlStatement selectStmt() {
        return withCurrentDbType(presetStatement != null ? presetStatement : SqlStatement.select(sql, args));
    }

    private SqlStatement withCurrentDbType(SqlStatement statement) {
        if (explicitDbType == null && statement.metadata().dbType() != null) {
            return statement;
        }
        DbType dbType = explicitDbType != null ? explicitDbType : peekDbType();
        if (dbType == null || statement.metadata().dbType() == dbType) {
            return statement;
        }
        SqlStatement updated = statement.withDbType(dbType);
        return updated.ast() == null ? updated : SqlRenderer.render(updated.ast(), updated.metadata());
    }

    private DbType peekDbType() {
        var peeked = kernel.peekDialect();
        return peeked != null ? peeked.dbType() : null;
    }

    private DbType currentDbType() {
        if (explicitDbType != null) {
            return explicitDbType;
        }
        if (presetStatement != null && presetStatement.metadata().dbType() != null) {
            return presetStatement.metadata().dbType();
        }
        DbType peeked = peekDbType();
        if (peeked != null) {
            return peeked;
        }
        if (resolvedDbType == null) {
            // 方言统一经 DialectStrategy 解析（cached 策略只探测一次）：
            // 原生 SQL 的占位符词法、操作类型推断与分页校验都需要真实方言，不能依赖字符启发式。
            resolvedDbType = kernel.dialect().dbType();
        }
        return resolvedDbType;
    }

    /**
     * 分页/截断类操作会在 SQL 末尾追加方言 LIMIT；若调用方 SQL 已自带 LIMIT，追加后会得到
     * {@code ... LIMIT 2 LIMIT ?} 这类非法 SQL。这里在发往数据库前给出明确错误。
     */
    private void rejectTrailingLimit(String operation) {
        if (sql != null && SqlFormatUtils.hasTopLevelPagination(sql, currentDbType())) {
            throw new IllegalStateException("Cannot apply " + operation
                    + "(): the SQL already contains a pagination clause (LIMIT/OFFSET/FETCH); remove it or use list()/one(): " + sql);
        }
    }

    // ===== 读收尾 =====

    /** 查询多行并映射为指定类型列表。 */
    public <T> List<T> list(Class<T> type) {
        return kernel.queryList(selectStmt(), mapperForType(type));
    }

    /** 查询多行并使用自定义 {@link RowMapper} 映射。 */
    public <T> List<T> list(RowMapper<T> rowMapper) {
        return kernel.queryList(selectStmt(), rowMapper);
    }

    /** 查询多行并映射为列名到列值的有序 Map 列表。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Map<String, Object>> listMap() {
        RowMapper<Map<String, Object>> mapper = (RowMapper) kernel.mapperFor(ResultType.MAP, Map.class);
        return list(mapper);
    }

    /**
     * 严格单行查询：0 行返回 {@link Optional#empty()}，1 行返回结果，多于 1 行抛 {@link IllegalStateException}。
     */
    public <T> Optional<T> one(Class<T> type) {
        return one(mapperForType(type));
    }

    /**
     * 严格单行查询（自定义 RowMapper）：0 行返回 {@link Optional#empty()}，多于 1 行抛 {@link IllegalStateException}。
     */
    public <T> Optional<T> one(RowMapper<T> rowMapper) {
        return Optional.ofNullable(kernel.queryOne(selectStmt(), rowMapper));
    }

    /**
     * 取首行（自动附加 {@code LIMIT 1} 语义）：0 行返回 {@link Optional#empty()}，多行只取第一行。
     */
    public <T> Optional<T> first(Class<T> type) {
        return first(mapperForType(type));
    }

    /**
     * 取首行（自定义 RowMapper，自动附加 {@code LIMIT 1} 语义）。
     */
    public <T> Optional<T> first(RowMapper<T> rowMapper) {
        rejectTrailingLimit("first");
        List<T> top = kernel.queryLimit(selectStmt(), rowMapper, 0, 1);
        return top.isEmpty() ? Optional.empty() : Optional.ofNullable(top.get(0));
    }

    /**
     * 查询单列标量值（如 {@code SELECT count(*)} / {@code SELECT max(age)}）。
     */
    public <T> Optional<T> value(Class<T> scalarType) {
        return Optional.ofNullable(kernel.queryOne(selectStmt(), new SingleColumnRowMapper<>(scalarType)));
    }

    /**
     * 执行计数 SQL 并返回 {@code long}（无结果时返回 0L）。
     */
    public long count() {
        return value(Long.class).orElse(0L);
    }

    /** 分页查询。 */
    public <T> PageResult<T> page(Class<T> type, PageRequest pageRequest) {
        return page(mapperForType(type), pageRequest);
    }

    /** 分页查询（自定义 RowMapper）。 */
    public <T> PageResult<T> page(RowMapper<T> rowMapper, PageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        rejectTrailingLimit("page");
        if (!pageRequest.isNeedTotal()) {
            int pageSize = pageRequest.getSize();
            List<T> fetched = kernel.queryLimit(
                    selectStmt(), rowMapper, pageRequest.getOffset(), (long) pageSize + 1L);
            boolean hasNext = fetched.size() > pageSize;
            List<T> rows = hasNext ? new ArrayList<>(fetched.subList(0, pageSize)) : fetched;
            return PageResult.of(rows, -1L, pageRequest.getPageNum(), pageSize, hasNext);
        }
        PageSlice<T> slice = kernel.queryPage(
                selectStmt(), rowMapper, pageRequest.getOffset(), pageRequest.getSize());
        return PageResult.of(slice.rows(), slice.total(), pageRequest.getPageNum(), pageRequest.getSize());
    }

    /** 按方言偏移量与行数截取结果列表。 */
    public <T> List<T> limit(Class<T> type, long offset, long limit) {
        rejectTrailingLimit("limit");
        return kernel.queryLimit(selectStmt(), mapperForType(type), offset, limit);
    }

    /**
     * 流式查询（调用方必须使用 {@code try-with-resources} 关闭返回的 {@link Stream}）。
     */
    public <T> Stream<T> stream(Class<T> type) {
        return kernel.queryStream(selectStmt(), mapperForType(type));
    }

    /**
     * 流式查询（自定义 RowMapper，调用方必须使用 {@code try-with-resources} 关闭返回的 {@link Stream}）。
     */
    public <T> Stream<T> stream(RowMapper<T> rowMapper) {
        return kernel.queryStream(selectStmt(), rowMapper);
    }

    /** 使用底层 {@link ResultSetExtractor} 提取自定义结果。 */
    public <T> T extract(ResultSetExtractor<T> extractor) {
        return kernel.query(selectStmt(), extractor);
    }

    // ===== 写收尾 =====

    /** 执行 UPDATE / DELETE / DDL 语句，返回受影响行数。 */
    public int update() {
        SqlStatement stmt = presetStatement != null ? presetStatement : dmlStmt(sql, args);
        return kernel.update(withCurrentDbType(stmt));
    }

    /** 执行 INSERT 语句，返回包含生成键与真实影响行数的 {@link MutationResult}。 */
    public MutationResult insert() {
        SqlStatement stmt = withCurrentDbType(
                presetStatement != null ? presetStatement : SqlStatement.insert(sql, args));
        var slice = kernel.insertExecute(stmt);
        return MutationResult.ofInsert(slice.affectedRows(), slice.generatedKey());
    }

    /** 使用当前 SQL 模板执行批量参数更新。 */
    public int[] batch(List<Object[]> batchArgs) {
        return batch(batchArgs, kernel.executionOptions().batchSize());
    }

    /** 使用当前 SQL 模板并按指定分片大小执行批量参数更新。 */
    public int[] batch(List<Object[]> batchArgs, int batchSize) {
        return batchResult(batchArgs, batchSize).batchCounts();
    }

    /**
     * 使用当前 SQL 模板执行批量参数更新，返回折算受影响行数（处理 {@code SUCCESS_NO_INFO = -2}）与生成键的 {@link MutationResult}。
     */
    public MutationResult batchResult(List<Object[]> batchArgs) {
        return batchResult(batchArgs, kernel.executionOptions().batchSize());
    }

    /**
     * 使用当前 SQL 模板并按指定分片大小执行批量参数更新，返回折算受影响行数的 {@link MutationResult}。
     */
    public MutationResult batchResult(List<Object[]> batchArgs, int batchSize) {
        DbType dbType = currentDbType();
        SqlStatement batchTemplate = withCurrentDbType(presetStatement != null
                ? presetStatement
                : SqlStatement.of(sql, List.of(),
                        StatementMetadata.builder(SqlFormatUtils.inferDmlOperation(sql, dbType))
                                .dbType(dbType)
                                .build()));
        if (batchArgs == null || batchArgs.isEmpty()) {
            if (!batchTemplate.parameters().isEmpty()) {
                throw new IllegalArgumentException("batch template must not carry parameters; pass them as rows");
            }
            return MutationResult.EMPTY;
        }
        var slice = kernel.batchExecute(BatchSqlStatement.fromTemplate(batchTemplate, batchArgs, batchSize));
        return MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    private SqlStatement dmlStmt(String sql, Object[] args) {
        DbType dbType = currentDbType();
        Operation op = SqlFormatUtils.inferDmlOperation(sql, dbType);
        SqlStatement stmt = switch (op) {
            case INSERT -> SqlStatement.insert(sql, args);
            case DELETE -> SqlStatement.delete(sql, args);
            case SELECT -> SqlStatement.select(sql, args);
            case TRUNCATE, DDL -> SqlStatement.of(sql, op, args);
            default -> SqlStatement.update(sql, args);
        };
        return dbType != null ? stmt.withDbType(dbType) : stmt;
    }

    /**
     * 对业务集合直接执行批量 SQL（使用映射函数提取每行参数数组）。
     *
     * @param items         业务对象集合
     * @param argsExtractor 将单个业务对象转换为参数数组的函数
     */
    public <T> int[] batch(Collection<T> items, Function<T, Object[]> argsExtractor) {
        return batch(items, kernel.executionOptions().batchSize(), argsExtractor);
    }

    /**
     * 对业务集合按指定分片大小执行批量 SQL。
     */
    public <T> int[] batch(Collection<T> items, int batchSize, Function<T, Object[]> argsExtractor) {
        return batchResult(items, batchSize, argsExtractor).batchCounts();
    }

    /**
     * 对业务集合直接执行批量 SQL，返回折算受影响行数的 {@link MutationResult}。
     */
    public <T> MutationResult batchResult(Collection<T> items, Function<T, Object[]> argsExtractor) {
        return batchResult(items, kernel.executionOptions().batchSize(), argsExtractor);
    }

    /**
     * 对业务集合按指定分片大小执行批量 SQL，返回折算受影响行数的 {@link MutationResult}。
     */
    public <T> MutationResult batchResult(Collection<T> items, int batchSize, Function<T, Object[]> argsExtractor) {
        Objects.requireNonNull(argsExtractor, "argsExtractor must not be null");
        if (items == null || items.isEmpty()) {
            return MutationResult.EMPTY;
        }
        List<Object[]> rows = new ArrayList<>(items.size());
        for (T item : items) {
            rows.add(argsExtractor.apply(item));
        }
        return batchResult(rows, batchSize);
    }

    private <T> RowMapper<T> mapperForType(Class<T> type) {
        Objects.requireNonNull(type, "result type must not be null");
        return kernel.mapperFor(ResultType.classify(type), type);
    }
}
