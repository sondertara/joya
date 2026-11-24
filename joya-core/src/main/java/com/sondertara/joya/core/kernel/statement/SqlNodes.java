package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@link SqlNode} 的强类型便捷工厂，供框架内部及自定义 {@code SqlRewriter} 构建不可变 AST 节点。
 */
public final class SqlNodes {

    private static final SqlNode.Func COUNT_STAR = new SqlNode.Func("COUNT", false, true, List.of());

    private SqlNodes() {
    }

    public static SqlNode.Raw raw(String sql) {
        return new SqlNode.Raw(sql, List.of());
    }

    public static SqlNode.Raw raw(String sql, List<SqlParameter> parameters) {
        return new SqlNode.Raw(sql, parameters);
    }

    public static SqlNode.Table table(String logicalName, String physicalName) {
        return new SqlNode.Table(logicalName, physicalName, "");
    }

    public static SqlNode.Table table(String logicalName, String physicalName, String alias) {
        return new SqlNode.Table(logicalName, physicalName, alias);
    }

    public static SqlNode.Column column(@Nullable String alias, String name) {
        return new SqlNode.Column(alias == null || alias.isBlank()
                ? null
                : new SqlNode.Table("", alias, alias), name);
    }

    public static SqlNode.Column column(SqlNode.@Nullable Table table, String name) {
        return new SqlNode.Column(table, name);
    }

    public static SqlNode.Param param(String name, @Nullable Object value, ParameterSource source) {
        return new SqlNode.Param(name, new SqlNode.Param.Bound(value), source);
    }

    /** 构造批量模板行槽位参数节点（仅用于构建 {@link BatchAstStatement} 模板 AST）。 */
    public static SqlNode.Param rowParam(String name, int rowIndex, ParameterSource source) {
        return new SqlNode.Param(name, new SqlNode.Param.RowSlot(rowIndex), source);
    }

    public static SqlNode.Func countStar() {
        return COUNT_STAR;
    }

    public static SqlNode.Func func(String name, SqlNode... args) {
        return new SqlNode.Func(name, false, false, args == null ? List.of() : List.of(args));
    }

    public static SqlNode.Func func(String name, boolean distinct, List<SqlNode> args) {
        return new SqlNode.Func(name, distinct, false, args);
    }

    public static SqlNode.Binary binary(SqlNode.BinaryOp op, SqlNode left, SqlNode right) {
        return new SqlNode.Binary(op, left, right);
    }

    public static SqlNode.Binary eq(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.EQ, left, right);
    }

    public static SqlNode.Binary ne(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.NE, left, right);
    }

    public static SqlNode.Binary gt(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.GT, left, right);
    }

    public static SqlNode.Binary gte(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.GTE, left, right);
    }

    public static SqlNode.Binary lt(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.LT, left, right);
    }

    public static SqlNode.Binary lte(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.LTE, left, right);
    }

    public static SqlNode.Binary add(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.ADD, left, right);
    }

    public static SqlNode.Binary sub(SqlNode left, SqlNode right) {
        return new SqlNode.Binary(SqlNode.BinaryOp.SUB, left, right);
    }

    public static SqlNode and(@Nullable SqlNode left, SqlNode right) {
        return left == null ? right : new SqlNode.Binary(SqlNode.BinaryOp.AND, left, right);
    }

    public static SqlNode or(@Nullable SqlNode left, SqlNode right) {
        return left == null ? right : new SqlNode.Binary(SqlNode.BinaryOp.OR, left, right);
    }

    public static SqlNode.Unary isNull(SqlNode operand) {
        return new SqlNode.Unary(SqlNode.UnaryOp.IS_NULL, operand);
    }

    public static SqlNode.Unary isNotNull(SqlNode operand) {
        return new SqlNode.Unary(SqlNode.UnaryOp.IS_NOT_NULL, operand);
    }

    public static SqlNode.Order order(SqlNode expression, SqlNode.SortDir direction) {
        return new SqlNode.Order(expression, direction);
    }

    public static SqlNode.Alias alias(SqlNode expression, String alias) {
        return new SqlNode.Alias(expression, alias);
    }

    public static SqlNode.Assignment assignment(String column, SqlNode value) {
        return new SqlNode.Assignment(column, value);
    }
}
