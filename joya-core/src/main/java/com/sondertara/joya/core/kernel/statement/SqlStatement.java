package com.sondertara.joya.core.kernel.statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 不可变物理 SQL 语句值对象 —— 编译完成后的最终执行与观测契约。
 *
 * <p>设计原则：
 * <ul>
 *   <li>纯粹物理视图：仅承载最终可送入 JDBC 执行的 SQL 文本、有序参数与语句元数据；
 *       结构化语法树由编译前的 {@link AstStatement} 独立承载。</li>
 *   <li>不可变：所有派生方法返回新实例，无副作用，天然线程安全。</li>
 *   <li>自描述：携带 {@code StatementMetadata} 供执行监听器与异常翻译器直接读取。</li>
 * </ul>
 *
 * @param sql        JDBC 风格 SQL（{@code ?} 占位符）
 * @param parameters 有序参数列表，与 SQL 中 {@code ?} 一一对应
 * @param metadata   语句级元数据（操作类型、表引用、实体类型等）
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

    /** 按显式操作类型从可变参数构建（DDL / TRUNCATE 等非 CRUD 语句使用）。 */
    public static SqlStatement of(String sql, Operation op, Object... args) {
        return fromArgs(sql, op, args);
    }

    /** 导出参数值列表。 */
    public List<Object> args() {
        if (parameters.isEmpty()) {
            return List.of();
        }
        List<Object> values = new ArrayList<>(parameters.size());
        for (SqlParameter parameter : parameters) {
            values.add(parameter.value());
        }
        return java.util.Collections.unmodifiableList(values);
    }

    // ===== 改写方法（返回新实例） =====

    /** 派生替换 SQL 文本的新实例。 */
    public SqlStatement withSql(String newSql) {
        return new SqlStatement(newSql, parameters, metadata);
    }

    /** 派生替换参数列表的新实例。 */
    public SqlStatement withParameters(List<SqlParameter> newParameters) {
        return new SqlStatement(sql, newParameters, metadata);
    }

    /** 派生替换元数据的新实例。 */
    public SqlStatement withMetadata(StatementMetadata newMetadata) {
        return new SqlStatement(sql, parameters, newMetadata);
    }

    /** 派生注入数据库类型的新实例。 */
    public SqlStatement withDbType(DbType newDbType) {
        return new SqlStatement(sql, parameters, metadata.withDbType(newDbType));
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
        return new SqlStatement(sql, toParameters(args == null ? List.of() : Arrays.asList(args)),
                StatementMetadata.of(op));
    }

    private static SqlStatement fromArgs(String sql, Operation op, List<Object> args) {
        return new SqlStatement(sql, toParameters(args), StatementMetadata.of(op));
    }

    private static List<SqlParameter> toParameters(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<SqlParameter> params = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            params.add(SqlParameter.of(i + 1, "arg_" + (i + 1), args.get(i), ParameterSource.RAW));
        }
        return params;
    }
}
