package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.query.Expr;
import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.utils.LambdaUtils;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 聚合与表达式静态工厂（建议 {@code import static com.sondertara.joya.core.query.dsl.Exprs.*;}）。
 */
public final class Exprs {

    private Exprs() {
    }

    /** {@code COUNT(*)}。 */
    public static Expr<Long> count() {
        return new Aggregate<>("COUNT(*)", null, false, Long.class);
    }

    /** {@code COUNT(col)}。 */
    public static <T> Expr<Long> count(IGetter<T, ?> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("COUNT", ColumnMetaProvider.fromGetter(column), false, Long.class);
    }

    /** {@code COUNT(DISTINCT col)}。 */
    public static <T> Expr<Long> countDistinct(IGetter<T, ?> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("COUNT", ColumnMetaProvider.fromGetter(column), true, Long.class);
    }

    /** {@code MAX(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R> Expr<R> max(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("MAX", ColumnMetaProvider.fromGetter(column), false, (Class<R>) valueType(column));
    }

    /** {@code MIN(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R> Expr<R> min(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("MIN", ColumnMetaProvider.fromGetter(column), false, (Class<R>) valueType(column));
    }

    /** {@code SUM(col)}，返回类型跟随列类型。 */
    @SuppressWarnings("unchecked")
    public static <T, R extends Number> Expr<R> sum(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("SUM", ColumnMetaProvider.fromGetter(column), false, (Class<R>) valueType(column));
    }

    /** {@code SUM(col)}，显式指定宽返回类型（如 {@code Long.class} 或 {@code BigDecimal.class}，防止整型求和窄化溢出）。 */
    public static <T, S extends Number> Expr<S> sum(IGetter<T, ? extends Number> column, Class<S> resultType) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");
        return new Aggregate<>("SUM", ColumnMetaProvider.fromGetter(column), false, boxed(resultType));
    }

    /** {@code SUM(col)} 并以 {@link Long} 接收结果。 */
    public static <T> Expr<Long> sumAsLong(IGetter<T, ? extends Number> column) {
        return sum(column, Long.class);
    }

    /** {@code SUM(col)} 并以 {@link java.math.BigDecimal} 接收结果。 */
    public static <T> Expr<java.math.BigDecimal> sumAsDecimal(IGetter<T, ? extends Number> column) {
        return sum(column, java.math.BigDecimal.class);
    }

    /**
     * 将普通列方法引用包装为 {@link Expr}（用于在不指定表别名的情况下调用 {@code .as(...)} 为列改名映射 DTO）。
     */
    public static <T, R> Expr<R> col(IGetter<T, R> column) {
        Objects.requireNonNull(column, "column must not be null");
        return buildColumnExpr(null, column);
    }

    /**
     * 显式绑定指定表别名的类型化列引用（用于自连接或同一目标表多次 JOIN 的场景，如 {@code col("t1", Emp::getName)}）。
     */
    public static <T, R> Expr<R> col(String tableAlias, IGetter<T, R> column) {
        Objects.requireNonNull(tableAlias, "tableAlias must not be null");
        Objects.requireNonNull(column, "column must not be null");
        String trimmedAlias = tableAlias.trim();
        if (!ColumnMetaProvider.SAFE_ALIAS.matcher(trimmedAlias).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table alias (potential SQL injection rejected): " + tableAlias);
        }
        return buildColumnExpr(trimmedAlias, column);
    }

    @SuppressWarnings("unchecked")
    private static <T, R> Expr<R> buildColumnExpr(@Nullable String tableAlias, IGetter<T, R> column) {
        Class<?> entityClass = LambdaUtils.getReceiverClass(column);
        String propertyName = LambdaUtils.getPropertyName(column);
        Class<R> type = (Class<R>) boxed(valueType(column));
        return new Expr<>() {
            @Override
            public Class<R> resultType() {
                return type;
            }

            @Override
            public String alias() {
                return propertyName;
            }

            @Override
            public Class<?> entityClass() {
                return entityClass;
            }

            @Override
            public @Nullable String tableAlias() {
                return tableAlias;
            }

            @Override
            public ColumnAlias resolveColumnAlias(RenderContext ctx) {
                return tableAlias == null
                        ? ctx.resolveGetterColumn(entityClass, propertyName)
                        : ctx.resolveAliasedGetterColumn(tableAlias, entityClass, propertyName);
            }

            @Override
            public String render(RenderContext ctx) {
                return resolveColumnAlias(ctx).identity();
            }
        };
    }

    /** {@code AVG(col)}，返回 {@code Double}。 */
    public static <T> Expr<Double> avg(IGetter<T, ? extends Number> column) {
        Objects.requireNonNull(column, "column must not be null");
        return new Aggregate<>("AVG", ColumnMetaProvider.fromGetter(column), false, Double.class);
    }

    /**
     * 逃生通道：内联一段原始 SQL 列/表达式（例如 {@code raw("COALESCE(t0.nick, t0.name)", String.class)}）。
     *
     * <p>若有动态参数，请使用 {@link #raw(String, Class, Object...)} 绑定参数，切勿拼接用户输入。
     */
    public static <R> Expr<R> raw(String sql, Class<R> type) {
        return raw(sql, type, false, (Object[]) null);
    }

    /**
     * 逃生通道：带参数绑定的原始 SQL 列/表达式（例如 {@code raw("COALESCE(t0.nick, ?)", String.class, "anon")}）。
     */
    public static <R> Expr<R> raw(String sql, Class<R> type, Object... args) {
        return raw(sql, type, false, args);
    }

    public static <R> Expr<R> raw(String sql, Class<R> type, boolean aggregate) {
        return raw(sql, type, aggregate, (Object[]) null);
    }

    /**
     * 逃生通道：带参数绑定的原始 SQL 聚合/非聚合表达式。
     */
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
        Class<R> boxed = boxed(type);
        return new Expr<>() {
            @Override
            public Class<R> resultType() {
                return boxed;
            }

            @Override
            public String render(RenderContext ctx) {
                return ctx != null && !ctx.isAliased()
                        ? SqlFormatUtils.stripTableAlias(jdbcSql)
                        : jdbcSql;
            }

            @Override
            public boolean isAggregate() {
                return aggregate;
            }

            @Override
            public List<SqlParameter> parameters() {
                return boundParams;
            }
        };
    }

    private static Class<?> valueType(IGetter<?, ?> column) {
        if (column instanceof Expr<?> expr) {
            return expr.resultType();
        }
        return LambdaUtils.getReturnType(column);
    }

    @SuppressWarnings("unchecked")
    private static <R> Class<R> boxed(Class<R> type) {
        return (Class<R>) LambdaUtils.boxed(type);
    }

    private record Aggregate<R>(String fn, ColumnMetaProvider column, boolean distinct, Class<R> resultType)
            implements Expr<R> {
        @Override
        public boolean isAggregate() {
            return true;
        }

        @Override
        public String render(RenderContext ctx) {
            if (column == null) {
                return fn;
            }
            String inner = column.resolveSql(ctx);
            return distinct ? fn + "(DISTINCT " + inner + ")" : fn + "(" + inner + ")";
        }
    }
}
