package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.SqlDataUtils;

import java.net.URI;
import java.net.URL;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
        return list(rs -> {
            try {
                ResultSetMetaData metaData = rs.getMetaData();
                int count = metaData.getColumnCount();
                Map<String, Object> row = new LinkedHashMap<>(count);
                for (int i = 1; i <= count; i++) {
                    String colName = SqlDataUtils.lookupColumnName(metaData, i);
                    row.put(colName, rs.getObject(i));
                }
                return row;
            } catch (SQLException e) {
                throw new DbException("Failed to map row to Map", e);
            }
        });
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
        return template.queryPage(selectStmt(), mapperFor(type), pageRequest);
    }

    /** 分页查询（快捷页码与页大小）。 */
    public <T> PageResult<T> page(Class<T> type, int page, int size) {
        return page(type, PageRequest.of(page, size));
    }

    /** 分页查询（自定义 RowMapper）。 */
    public <T> PageResult<T> page(RowMapper<T> rowMapper, PageRequest pageRequest) {
        return template.queryPage(selectStmt(), rowMapper, pageRequest);
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

    /** 执行 INSERT 语句，返回包含生成键与影响行数的 {@link MutationResult}。 */
    public MutationResult insert() {
        SqlStatement stmt = presetStatement != null ? presetStatement : SqlStatement.insert(sql, args);
        Object key = template.insert(stmt);
        return MutationResult.ofInsert(1, key);
    }

    /** 使用当前 SQL 模板执行批量参数更新。 */
    public int[] batch(List<Object[]> batchArgs) {
        SqlStatement batchTemplate = presetStatement != null
                ? presetStatement
                : SqlStatement.of(sql, List.of(), StatementMetadata.of(Operation.UPDATE));
        return template.batchUpdate(batchTemplate, batchArgs);
    }

    public static <T> RowMapper<T> mapperFor(Class<T> type) {
        if (isScalarType(type)) {
            return new SingleColumnRowMapper<>(type);
        }
        return new BeanRowMapper<>(type);
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
