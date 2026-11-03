package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@link SqlNode} 的便捷工厂，避免扩展作者到处 new 嵌套 record。
 */
public final class SqlNodes {

    private SqlNodes() {
    }

    public static SqlNode raw(String sql) {
        return new SqlNode.Raw(sql, List.of());
    }

    public static SqlNode raw(String sql, List<SqlParameter> parameters) {
        return new SqlNode.Raw(sql, parameters);
    }

    public static SqlNode.Table table(String logicalName, String physicalName) {
        return new SqlNode.Table(logicalName, physicalName, "");
    }

    public static SqlNode.Table table(String logicalName, String physicalName, String alias) {
        return new SqlNode.Table(logicalName, physicalName, alias);
    }

    public static SqlNode.Column column(String alias, String name) {
        return new SqlNode.Column(alias == null || alias.isBlank()
                ? null
                : new SqlNode.Table("", alias, alias), name);
    }

    public static SqlNode.Column column(SqlNode.Table table, String name) {
        return new SqlNode.Column(table, name);
    }

    public static SqlNode.Param param(String name, @Nullable Object value, ParameterSource source) {
        return new SqlNode.Param(name, value, source);
    }

    public static SqlNode.Binary binary(String op, @Nullable SqlNode left, SqlNode right) {
        return new SqlNode.Binary(op, left, right);
    }

    public static SqlNode.Binary eq(SqlNode left, SqlNode right) {
        return new SqlNode.Binary("=", left, right);
    }

    public static SqlNode and(@Nullable SqlNode left, SqlNode right) {
        return left == null ? right : new SqlNode.Binary("AND", left, right);
    }

    public static SqlNode or(@Nullable SqlNode left, SqlNode right) {
        return left == null ? right : new SqlNode.Binary("OR", left, right);
    }

    public static SqlNode.Alias alias(SqlNode expression, String alias) {
        return new SqlNode.Alias(expression, alias);
    }

    public static SqlNode.Assignment assignment(String column, SqlNode value) {
        return new SqlNode.Assignment(column, value);
    }
}
