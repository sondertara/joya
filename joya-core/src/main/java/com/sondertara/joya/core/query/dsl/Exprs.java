package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.Expr;
import com.sondertara.joya.utils.LambdaUtils;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 聚合与类型化表达式静态工厂（产出密封代数类型 {@link Expr}，直接编译为结构化 AST）。
 */
public final class Exprs {

    private static final Expr<Long> COUNT_ALL = new Expr.AggregateExpr<>("COUNT", null, false, null, Long.class);

    private Exprs() {
    }

    /** {@code COUNT(*)}。 */
    public static Expr<Long> count() {
        return COUNT_ALL;
    }

    /** {@code COUNT(col)}。 */
    public static <T> Expr<Long> count(IGetter<T, ?> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Expr.AggregateExpr<>("COUNT", ColumnMetaProvider.fromGetter(column), false, null, Long.class);
    }

    /** {@code COUNT(DISTINCT col)}。 */
    public static <T> Expr<Long> countDistinct(IGetter<T, ?> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Expr.AggregateExpr<>("COUNT", ColumnMetaProvider.fromGetter(column), true, null, Long.class);
    }

    /** {@code MAX(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R> Expr<R> max(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        return new Expr.AggregateExpr<>("MAX", provider, false, null, (Class<R>) resolveValueType(provider, column));
    }

    /** {@code MIN(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R> Expr<R> min(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        return new Expr.AggregateExpr<>("MIN", provider, false, null, (Class<R>) resolveValueType(provider, column));
    }

    /** {@code SUM(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R extends Number> Expr<R> sum(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        return new Expr.AggregateExpr<>("SUM", provider, false, null, (Class<R>) resolveValueType(provider, column));
    }

    /** {@code SUM(col)}，显式指定宽返回类型（如 {@code Long.class} 或 {@code BigDecimal.class}）。 */
    public static <T, S extends Number> Expr<S> sum(IGetter<T, ? extends Number> column, Class<S> resultType) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");
        return new Expr.AggregateExpr<>("SUM", ColumnMetaProvider.fromGetter(column), false, null, boxed(resultType));
    }

    /** {@code SUM(col)} 并以 {@link Long} 接收结果。 */
    public static <T> Expr<Long> sumAsLong(IGetter<T, ? extends Number> column) {
        return sum(column, Long.class);
    }

    /** {@code SUM(col)} 并以 {@link BigDecimal} 接收结果。 */
    public static <T> Expr<BigDecimal> sumAsDecimal(IGetter<T, ? extends Number> column) {
        return sum(column, BigDecimal.class);
    }

    /** 将普通列方法引用包装为 {@link Expr}，便于通过 {@code .as(...)} 指定 DTO 映射别名。 */
    public static <T, R> Expr<R> col(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        return buildColumnExpr(null, column);
    }

    /** 显式绑定指定表别名的类型化列引用（自连接或同表多次 JOIN，如 {@code col("t1", Emp::getName)}）。 */
    public static <T, R> Expr<R> col(String tableAlias, IGetter<T, R> column) {
        Objects.requireNonNull(tableAlias, "tableAlias must not be null");
        Objects.requireNonNull(column, "column must not be null");
        String trimmedAlias = tableAlias.trim();
        if (!ColumnMetaProvider.isSafeAlias(trimmedAlias)) {
            throw new IllegalArgumentException(
                    "Invalid table alias (potential SQL injection rejected): " + tableAlias);
        }
        return buildColumnExpr(trimmedAlias, column);
    }

    @SuppressWarnings("unchecked")
    private static <T, R> Expr<R> buildColumnExpr(@Nullable String tableAlias, IGetter<T, R> column) {
        ColumnMetaProvider provider = ColumnMetaProvider.fromGetter(column);
        Class<?> entityClass = Objects.requireNonNull(provider.entityClass(),
                "Exprs.col requires an entity getter method reference");
        String propertyName = Objects.requireNonNull(provider.propertyName(),
                "Exprs.col requires an entity getter method reference");
        Class<R> type = (Class<R>) boxed(resolveValueType(provider, column));
        return new Expr.ColumnExpr<>(entityClass, propertyName, tableAlias, propertyName, type);
    }

    /** {@code AVG(col)}，返回 {@link Double}。 */
    public static <T> Expr<Double> avg(IGetter<T, ? extends Number> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Expr.AggregateExpr<>("AVG", ColumnMetaProvider.fromGetter(column), false, null, Double.class);
    }

    public static <R> Expr<R> raw(String sql, Class<R> type) {
        return raw(sql, type, false, (Object[]) null);
    }

    public static <R> Expr<R> raw(String sql, Class<R> type, Object... args) {
        return raw(sql, type, false, args);
    }

    public static <R> Expr<R> raw(String sql, Class<R> type, boolean aggregate) {
        return raw(sql, type, aggregate, (Object[]) null);
    }

    public static <R> Expr<R> raw(String sql, Class<R> type, boolean aggregate, @Nullable Object... args) {
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(type, "type must not be null");
        String jdbcSql = SqlFormatUtils.getJdbcStyleSql(sql.trim());
        int expected = SqlFormatUtils.countPlaceholders(jdbcSql);
        int actual = args == null ? 0 : args.length;
        if (expected != actual) {
            throw new IllegalArgumentException("Placeholder count mismatch in Exprs.raw(\"" + sql
                    + "\"): expected " + expected + " parameter(s), got " + actual);
        }
        List<SqlParameter> boundParams;
        if (actual == 0) {
            boundParams = List.of();
        } else {
            List<SqlParameter> list = new ArrayList<>(actual);
            for (int i = 0; i < actual; i++) {
                list.add(SqlParameter.of(i + 1, "expr_arg_" + (i + 1), args[i], ParameterSource.RAW));
            }
            boundParams = List.copyOf(list);
        }
        return new Expr.RawExpr<>(jdbcSql, boundParams, aggregate, null, boxed(type));
    }

    private static Class<?> resolveValueType(ColumnMetaProvider provider, IGetter<?, ?> fallback) {
        if (provider.valueType() != null) {
            return provider.valueType();
        }
        if (fallback instanceof Expr<?> expr) {
            return expr.resultType();
        }
        return LambdaUtils.resolve(fallback).returnType();
    }

    @SuppressWarnings("unchecked")
    private static <R> Class<R> boxed(Class<R> type) {
        return (Class<R>) LambdaUtils.boxed(type);
    }
}
