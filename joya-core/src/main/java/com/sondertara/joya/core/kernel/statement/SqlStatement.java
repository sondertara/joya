package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.kernel.dialect.DbType;

import java.util.List;
import java.util.Objects;

/**
 * 不可变 SQL 语句值对象 —— 全框架唯一的 SQL 契约。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>不可变：所有改写方法返回新实例，无副作用，天然线程安全。</li>
 *   <li>自描述：携带 {@code tables} 供拦截器（分片/租户）使用，无需正则解析 SQL。</li>
 *   <li>参数有序：{@code parameters} 携带名称、来源与绑定策略，替代裸 {@code Object}。</li>
 *   <li>零成本：{@code record} 无继承、无反射，{@code GraalVM native} 友好。</li>
 * </ul>
 *
 * @param sql            JDBC 风格 SQL（{@code ?} 占位符）
 * @param parameters     有序参数列表，与 SQL 中 ? 一一对应
 * @param metadata       语句级元数据（操作类型、表引用、实体类型等）
 */
public record SqlStatement(
        String sql,
        List<SqlParameter> parameters,
        StatementMetadata metadata
) {

    public SqlStatement {
        sql = sql == null ? "" : sql.trim();
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
    }

    /** 从有序参数列表构建（推荐）。 */
    public static SqlStatement of(String sql, List<SqlParameter> parameters, StatementMetadata metadata) {
        return new SqlStatement(sql, parameters, metadata);
    }

    /** 导出参数值列表。 */
    public List<Object> args() {
        return parameters.stream().map(SqlParameter::value).toList();
    }

    // ===== 改写方法（返回新实例） =====

    /** 改写 SQL 文本，返回新实例。 */
    public SqlStatement withSql(String newSql) {
        return new SqlStatement(newSql, parameters, metadata);
    }

    /** 改写参数列表，返回新实例。 */
    public SqlStatement withParameters(List<SqlParameter> newParameters) {
        return new SqlStatement(sql, newParameters, metadata);
    }

    /** 改写元数据，返回新实例。 */
    public SqlStatement withMetadata(StatementMetadata newMetadata) {
        return new SqlStatement(sql, parameters, newMetadata);
    }

    /** 改写数据库类型，返回新实例。 */
    public SqlStatement withDbType(DbType newDbType) {
        return new SqlStatement(sql, parameters, metadata.withDbType(newDbType));
    }

    /** 追加参数（如租户拦截器追加 {@code tenant_id = ?}），自动重排 index，返回新实例。 */
    public SqlStatement appendParameter(String name, Object value, ParameterSource source) {
        List<SqlParameter> newParams = new java.util.ArrayList<>(parameters);
        int index = newParams.size() + 1;
        newParams.add(SqlParameter.of(index, name, value, source));
        return new SqlStatement(sql, newParams, metadata);
    }

    /** 替换指定 index 处的参数，返回新实例。 */
    public SqlStatement replaceParameter(int index, SqlParameter parameter) {
        List<SqlParameter> newParams = new java.util.ArrayList<>(parameters);
        int target = index - 1;
        if (target < 0 || target >= newParams.size()) {
            throw new IndexOutOfBoundsException("parameter index out of range: " + index);
        }
        newParams.set(target, SqlParameter.of(index, parameter.name(), parameter.value(), parameter.source()));
        return new SqlStatement(sql, newParams, metadata);
    }

    // ===== 静态工厂：提升调用可读性，并强制表达操作类型 =====

    public static SqlStatement select(String sql, Object... args) {
        return fromArgs(sql, Operation.SELECT, args);
    }

    public static SqlStatement select(String sql, List<Object> args) {
        return fromArgs(sql, Operation.SELECT, args);
    }

    public static SqlStatement insert(String sql, Object... args) {
        return fromArgs(sql, Operation.INSERT, args);
    }

    public static SqlStatement insert(String sql, List<Object> args) {
        return fromArgs(sql, Operation.INSERT, args);
    }

    public static SqlStatement update(String sql, Object... args) {
        return fromArgs(sql, Operation.UPDATE, args);
    }

    public static SqlStatement update(String sql, List<Object> args) {
        return fromArgs(sql, Operation.UPDATE, args);
    }

    public static SqlStatement delete(String sql, Object... args) {
        return fromArgs(sql, Operation.DELETE, args);
    }

    public static SqlStatement delete(String sql, List<Object> args) {
        return fromArgs(sql, Operation.DELETE, args);
    }

    private static SqlStatement fromArgs(String sql, Operation op, Object... args) {
        return new SqlStatement(sql, toParameters(args == null ? List.of() : java.util.Arrays.asList(args)),
                StatementMetadata.of(op));
    }

    private static SqlStatement fromArgs(String sql, Operation op, List<Object> args) {
        return new SqlStatement(sql, toParameters(args), StatementMetadata.of(op));
    }

    private static List<SqlParameter> toParameters(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<SqlParameter> params = new java.util.ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            params.add(SqlParameter.of(i + 1, "arg_" + (i + 1), args.get(i), ParameterSource.RAW));
        }
        return params;
    }
}
