package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.MapRowMapper;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.PageSlice;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.result.MutationResult;

import java.net.URI;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 原生参数化 SQL 链式操作门面 —— 由 {@link DataOps#sql(String, Object...)} 创建。
 *
 * <p>统一承载原生 SQL 的读（{@code list/one/first/value/page/stream/extract}）与写（{@code update/insert/batch}）收尾动作，
 * 所有执行均经过 {@link JoyaTemplate} 及其拦截器链。
 */
public final class SqlOps {

    private final JoyaTemplate template;
    private final String sql;
    private final Object[] args;
    private final SqlStatement presetStatement;

    public SqlOps(JoyaTemplate template, String sql, Object... args) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.sql = Objects.requireNonNull(sql, "sql must not be null");
        this.args = args == null ? new Object[0] : args.clone();
        this.presetStatement = null;
    }

    public SqlOps(JoyaTemplate template, SqlStatement statement) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.presetStatement = Objects.requireNonNull(statement, "statement must not be null");
        this.sql = statement.sql();
        this.args = statement.args().toArray();
    }

    private SqlStatement selectStmt() {
        return presetStatement != null ? presetStatement : SqlStatement.select(sql, args);
    }

    // ===== 读收尾 =====

    /** 查询多行并映射为指定类型列表。 */
    public <T> List<T> list(Class<T> type) {
        return template.queryList(selectStmt(), mapperFor(type));
    }

    /** 查询多行并使用自定义 {@link RowMapper} 映射。 */
    public <T> List<T> list(RowMapper<T> rowMapper) {
        return template.queryList(selectStmt(), rowMapper);
    }

    /** 查询多行并映射为列名到列值的有序 Map 列表。 */
    public List<Map<String, Object>> listMap() {
        return list(new MapRowMapper());
    }

    /**
     * 严格单行查询：0 行返回 {@link Optional#empty()}，1 行返回结果，多于 1 行抛 {@link IllegalStateException}。
     */
    public <T> Optional<T> one(Class<T> type) {
        return one(mapperFor(type));
    }

    /**
     * 严格单行查询（自定义 RowMapper）：0 行返回 {@link Optional#empty()}，多于 1 行抛 {@link IllegalStateException}。
     */
    public <T> Optional<T> one(RowMapper<T> rowMapper) {
        return Optional.ofNullable(template.queryOne(selectStmt(), rowMapper));
    }

    /**
     * 取首行（自动附加 {@code LIMIT 1} 语义）：0 行返回 {@link Optional#empty()}，多行只取第一行。
     */
    public <T> Optional<T> first(Class<T> type) {
        return first(mapperFor(type));
    }

    /**
     * 取首行（自定义 RowMapper，自动附加 {@code LIMIT 1} 语义）。
     */
    public <T> Optional<T> first(RowMapper<T> rowMapper) {
        List<T> top = template.queryLimit(selectStmt(), rowMapper, 0, 1);
        return top.isEmpty() ? Optional.empty() : Optional.ofNullable(top.get(0));
    }

    /**
     * 查询单列标量值（如 {@code SELECT count(*)} / {@code SELECT max(age)}）。
     */
    public <T> Optional<T> value(Class<T> scalarType) {
        return Optional.ofNullable(template.queryOne(selectStmt(), new SingleColumnRowMapper<>(scalarType)));
    }

    /**
     * 执行计数 SQL 并返回 {@code long}（无结果时返回 0L）。
     */
    public long count() {
        return value(Long.class).orElse(0L);
    }

    /** 分页查询。 */
    public <T> PageResult<T> page(Class<T> type, PageRequest pageRequest) {
        return page(mapperFor(type), pageRequest);
    }

    /** 分页查询（快捷页码与页大小）。 */
    public <T> PageResult<T> page(Class<T> type, int page, int size) {
        return page(type, PageRequest.of(page, size));
    }

    /** 分页查询（自定义 RowMapper）。 */
    public <T> PageResult<T> page(RowMapper<T> rowMapper, PageRequest pageRequest) {
        Objects.requireNonNull(pageRequest, "pageRequest must not be null");
        PageSlice<T> slice = template.queryPage(
                selectStmt(), rowMapper, pageRequest.getOffsetAsLong(), pageRequest.getSize());
        return PageResult.of(slice.rows())
                .pagination(pageRequest.getPage(), pageRequest.getSize())
                .total(slice.total())
                .build();
    }

    /** 按方言偏移量与行数截取结果列表。 */
    public <T> List<T> limit(Class<T> type, long offset, long limit) {
        return template.queryLimit(selectStmt(), mapperFor(type), offset, limit);
    }

    /**
     * 流式查询（调用方必须使用 {@code try-with-resources} 关闭返回的 {@link Stream}）。
     */
    public <T> Stream<T> stream(Class<T> type) {
        return template.queryStream(selectStmt(), mapperFor(type));
    }

    /**
     * 流式查询（自定义 RowMapper，调用方必须使用 {@code try-with-resources} 关闭返回的 {@link Stream}）。
     */
    public <T> Stream<T> stream(RowMapper<T> rowMapper) {
        return template.queryStream(selectStmt(), rowMapper);
    }

    /** 使用底层 {@link ResultSetExtractor} 提取自定义结果。 */
    public <T> T extract(ResultSetExtractor<T> extractor) {
        return template.query(selectStmt(), extractor);
    }

    // ===== 写收尾 =====

    /** 执行 UPDATE / DELETE / DDL 语句，返回受影响行数。 */
    public int update() {
        SqlStatement stmt = presetStatement != null ? presetStatement : SqlStatement.update(sql, args);
        return template.update(stmt);
    }

    /** 执行 INSERT 语句，返回包含生成键与真实影响行数的 {@link MutationResult}。 */
    public MutationResult insert() {
        SqlStatement stmt = presetStatement != null ? presetStatement : SqlStatement.insert(sql, args);
        var slice = template.insertExecute(stmt);
        return MutationResult.ofInsert(slice.affectedRows(), slice.generatedKey());
    }

    /** 使用当前 SQL 模板执行批量参数更新。 */
    public int[] batch(List<Object[]> batchArgs) {
        return batch(batchArgs, template.executionOptions().batchSize());
    }

    /** 使用当前 SQL 模板并按指定分片大小执行批量参数更新。 */
    public int[] batch(List<Object[]> batchArgs, int batchSize) {
        SqlStatement batchTemplate = presetStatement != null
                ? presetStatement
                : SqlStatement.of(sql, List.of(), StatementMetadata.of(Operation.UPDATE));
        return template.batchUpdate(batchTemplate, batchArgs, batchSize);
    }

    /**
     * 对业务集合直接执行批量 SQL（使用映射函数提取每行参数数组）。
     *
     * @param items         业务对象集合
     * @param argsExtractor 将单个业务对象转换为参数数组的函数
     */
    public <T> int[] batch(Collection<T> items, Function<T, Object[]> argsExtractor) {
        return batch(items, template.executionOptions().batchSize(), argsExtractor);
    }

    /**
     * 对业务集合按指定分片大小执行批量 SQL。
     */
    public <T> int[] batch(Collection<T> items, int batchSize, Function<T, Object[]> argsExtractor) {
        Objects.requireNonNull(argsExtractor, "argsExtractor must not be null");
        if (items == null || items.isEmpty()) {
            return new int[0];
        }
        List<Object[]> rows = new ArrayList<>(items.size());
        for (T item : items) {
            rows.add(argsExtractor.apply(item));
        }
        return batch(rows, batchSize);
    }

    public static <T> RowMapper<T> mapperFor(Class<T> type) {
        if (isScalarType(type)) {
            return new SingleColumnRowMapper<>(type);
        }
        return BeanRowMapper.of(type);
    }

    public static boolean isScalarType(Class<?> type) {
        if (type == null) {
            return false;
        }
        return type.isPrimitive()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class == type
                || Character.class == type
                || Date.class.isAssignableFrom(type)
                || Temporal.class.isAssignableFrom(type)
                || UUID.class == type
                || byte[].class == type
                || Blob.class.isAssignableFrom(type)
                || Clob.class.isAssignableFrom(type)
                || Enum.class.isAssignableFrom(type)
                || URI.class == type
                || URL.class == type
                || Object.class == type;
    }
}
