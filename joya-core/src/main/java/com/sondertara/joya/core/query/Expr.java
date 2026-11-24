package com.sondertara.joya.core.query;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.utils.LambdaUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 类型化 SQL 查询列与聚合表达式契约（密封代数类型，直接编译为结构化 {@link SqlNode}，零字符串预渲染）。
 *
 * <p>实现 {@link IGetter} 仅作为投影/分组/排序 DSL 的编译期类型标记，禁止在内存中直接调用 {@link #apply(Object)}。
 *
 * @param <R> 表达式结果的 Java 类型
 */
public sealed interface Expr<R> extends IGetter<Object, R>
        permits Expr.ColumnExpr, Expr.AggregateExpr, Expr.RawExpr {

    /** 表达式结果的装箱 Java 类型。 */
    Class<R> resultType();

    /** 是否为聚合函数表达式（供 COUNT / 分页子查询判定）。 */
    default boolean isAggregate() {
        return false;
    }

    /** 可选的投影列别名（用于 {@code list(X.class)} 按名称映射）。 */
    @Nullable
    default String alias() {
        return null;
    }

    /** 在给定符号作用域内解析为结构化 {@link SqlNode}（不含外层 {@code AS} 别名包装）。 */
    SqlNode toNode(TableScope scope);

    /**
     * 为表达式指定投影列别名（{@code SELECT expr AS alias}）；非合法纯标识符立即拒绝以杜绝注入。
     */
    Expr<R> as(String alias);

    /**
     * 使用目标 DTO 方法引用指定类型安全列别名：{@code Exprs.count().as(OrderVO::getOrderCount)}。
     */
    default <D> Expr<R> as(IGetter<D, R> targetField) {
        Objects.requireNonNull(targetField, "targetField must not be null");
        LambdaUtils.LambdaMeta meta = LambdaUtils.resolve(targetField);
        Class<?> expected = meta.returnType();
        Class<R> actual = resultType();
        if (actual != null && expected != null && !actual.isAssignableFrom(expected)) {
            throw new IllegalArgumentException(
                    "Alias target [" + meta.receiverClass().getSimpleName()
                            + "::" + meta.propertyName() + "] returns ["
                            + expected.getName() + "] but expression result type is [" + actual.getName() + "]");
        }
        return as(meta.propertyName());
    }

    @Override
    default R apply(Object source) {
        throw new UnsupportedOperationException("Expr is a SQL expression marker and cannot be invoked in memory");
    }

    private static String validateAlias(String alias) {
        Objects.requireNonNull(alias, "alias must not be null");
        String trimmed = alias.trim();
        if (!ColumnMetaProvider.isSafeAlias(trimmed)) {
            throw new IllegalArgumentException(
                    "Invalid column alias (potential SQL injection rejected): " + alias);
        }
        return trimmed;
    }

    /** 绑定可选表别名的类型化实体列引用（对应 {@code Exprs.col(...)}）。 */
    record ColumnExpr<R>(
            Class<?> entityClass,
            String propertyName,
            @Nullable String tableAlias,
            @Nullable String alias,
            Class<R> resultType
    ) implements Expr<R> {

        public ColumnExpr {
            Objects.requireNonNull(entityClass, "entityClass must not be null");
            Objects.requireNonNull(propertyName, "propertyName must not be null");
            Objects.requireNonNull(resultType, "resultType must not be null");
        }

        public TableScope.ResolvedColumn resolveColumn(TableScope scope) {
            return tableAlias == null
                    ? scope.resolveGetterColumn(entityClass, propertyName)
                    : scope.resolveAliasedGetterColumn(tableAlias, entityClass, propertyName);
        }

        @Override
        public SqlNode toNode(TableScope scope) {
            return resolveColumn(scope).toNode();
        }

        @Override
        public Expr<R> as(String newAlias) {
            return new ColumnExpr<>(entityClass, propertyName, tableAlias, validateAlias(newAlias), resultType);
        }
    }

    /** 结构化聚合函数表达式（{@code COUNT} / {@code MAX} / {@code MIN} / {@code SUM} / {@code AVG}）。 */
    record AggregateExpr<R>(
            String fn,
            @Nullable ColumnMetaProvider column,
            boolean distinct,
            @Nullable String alias,
            Class<R> resultType
    ) implements Expr<R> {

        public AggregateExpr {
            Objects.requireNonNull(fn, "fn must not be null");
            Objects.requireNonNull(resultType, "resultType must not be null");
        }

        @Override
        public boolean isAggregate() {
            return true;
        }

        @Override
        public SqlNode toNode(TableScope scope) {
            if (column == null) {
                return new SqlNode.Func(fn, false, true, List.of());
            }
            return new SqlNode.Func(fn, distinct, false, List.of(column.resolveNode(scope)));
        }

        @Override
        public Expr<R> as(String newAlias) {
            return new AggregateExpr<>(fn, column, distinct, validateAlias(newAlias), resultType);
        }
    }

    /** 参数化原生 SQL 表达式逃生通道（对应 {@code Exprs.raw(...)}）。 */
    record RawExpr<R>(
            String jdbcSql,
            List<SqlParameter> parameters,
            boolean aggregate,
            @Nullable String alias,
            Class<R> resultType
    ) implements Expr<R> {

        public RawExpr {
            Objects.requireNonNull(jdbcSql, "jdbcSql must not be null");
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            Objects.requireNonNull(resultType, "resultType must not be null");
        }

        @Override
        public boolean isAggregate() {
            return aggregate;
        }

        @Override
        public SqlNode toNode(TableScope scope) {
            String rendered = !scope.isAliased()
                    ? SqlFormatUtils.stripTableAlias(jdbcSql)
                    : jdbcSql;
            return new SqlNode.Raw(rendered, parameters);
        }

        @Override
        public Expr<R> as(String newAlias) {
            return new RawExpr<>(jdbcSql, parameters, aggregate, validateAlias(newAlias), resultType);
        }
    }
}
