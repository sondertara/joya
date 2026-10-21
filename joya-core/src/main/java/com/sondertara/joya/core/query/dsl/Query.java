package com.sondertara.joya.core.query.dsl;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.jdbc.resultset.mapper.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * 统一类型安全查询流（三段式：{@code from(T)} → 行级子句 → 可选 {@code select(...)} → 终结方法）。
 *
 * <ul>
 *   <li>构建期仅收集持有 {@link ColumnMetaProvider} 的惰性结构，子句顺序完全正交（{@code where} 可写在 {@code join} 之前）。</li>
 *   <li>未写 {@code select}：终结方法直接返回主表实体 {@code T}，或按目标类 {@code Class<V>} 字段名自动推列。</li>
 *   <li>写了 {@code select}：返回 {@link Select1}..{@link Select8} / {@link SelectN}，支持单列直接取值、
 *       {@code list(X.class)} 按名字装回实体/JavaBean、{@code list(X::new)} 按位置构造 Record/VO。</li>
 * </ul>
 *
 * @param <T> 主表实体类型
 */
public final class Query<T> implements QueryScope {

    private record OrderSpec(ColumnMetaProvider column, OrderParam.OrderBy direction) {
    }

    private final JoyaTemplate template;
    private final Class<T> rootType;
    private final JoinCriterion joinCriterion = new JoinCriterion();
    private final WhereCriterion whereCriterion = new WhereCriterion(LinkType.AND);
    private final List<ColumnMetaProvider> groupByColumns = new ArrayList<>();
    private final WhereCriterion havingCriterion = new WhereCriterion(LinkType.AND);
    private final List<OrderSpec> orderSpecs = new ArrayList<>();
    private boolean distinct;
    private Integer offset;
    private Integer limit;

    public Query(JoyaTemplate template, Class<T> rootType) {
        this.template = template;
        this.rootType = Objects.requireNonNull(rootType, "rootType must not be null");
    }

    /**
     * 离线创建查询构建器（不绑定数据源，仅用于构造与检视 {@link #toSqlStatement()}）。
     */
    public static <T> Query<T> of(Class<T> rootType) {
        return new Query<>(null, rootType);
    }

    @Override
    public Class<T> rootType() {
        return rootType;
    }

    RenderContext renderContext() {
        return RenderContext.forQuery(
                rootType,
                joinCriterion.getJoinSegments(),
                template != null ? template.settings() : com.sondertara.joya.core.JoyaSettings.DEFAULT
        );
    }

    // ===== 1. 行级子句（全部返回 Query<T>，可任意顺序组合） =====

    public <L, R> Query<T> join(IGetter<L, ?> left, IGetter<R, ?> right) {
        joinCriterion.join(left, right);
        return this;
    }

    public <L, R> Query<T> leftJoin(IGetter<L, ?> left, IGetter<R, ?> right) {
        joinCriterion.leftJoin(left, right);
        return this;
    }

    public <L, R> Query<T> rightJoin(IGetter<L, ?> left, IGetter<R, ?> right) {
        joinCriterion.rightJoin(left, right);
        return this;
    }

    @Override
    public Query<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        whereCriterion.and(condition);
        return this;
    }

    public Query<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        if (!criterion.isEmpty()) {
            whereCriterion.and(w -> criterion);
        }
        return this;
    }

    public <E1> Query<T> groupBy(IGetter<E1, ?> c1) {
        groupByColumns.add(ColumnMetaProvider.fromGetter(Objects.requireNonNull(c1)));
        return this;
    }

    public <E1, E2> Query<T> groupBy(IGetter<E1, ?> c1, IGetter<E2, ?> c2) {
        return groupBy(c1).groupBy(c2);
    }

    public <E1, E2, E3> Query<T> groupBy(IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3) {
        return groupBy(c1).groupBy(c2).groupBy(c3);
    }

    public <E1, E2, E3, E4> Query<T> groupBy(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4) {
        return groupBy(c1).groupBy(c2).groupBy(c3).groupBy(c4);
    }

    @SafeVarargs
    public final <E> Query<T> groupBy(IGetter<E, ?>... columns) {
        if (columns != null) {
            for (IGetter<E, ?> column : columns) {
                if (column != null) {
                    groupByColumns.add(ColumnMetaProvider.fromGetter(column));
                }
            }
        }
        return this;
    }

    public Query<T> having(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "having condition must not be null");
        havingCriterion.and(condition);
        return this;
    }

    public <E> Query<T> orderByAsc(IGetter<E, ?> column) {
        return orderBy(column, OrderParam.OrderBy.ASC);
    }

    public <E> Query<T> orderByDesc(IGetter<E, ?> column) {
        return orderBy(column, OrderParam.OrderBy.DESC);
    }

    public <E> Query<T> orderBy(IGetter<E, ?> column, OrderParam.OrderBy direction) {
        Objects.requireNonNull(column, "orderBy column must not be null");
        Objects.requireNonNull(direction, "orderBy direction must not be null");
        orderSpecs.add(new OrderSpec(ColumnMetaProvider.fromGetter(column), direction));
        return this;
    }

    @Override
    public Query<T> orderBy(OrderParam... orderParams) {
        if (orderParams != null) {
            for (OrderParam param : orderParams) {
                if (param == null) {
                    continue;
                }
                ColumnMetaProvider provider = ColumnMetaProvider.fromName(param.getFieldName());
                OrderParam.OrderBy dir = param.getOrderType() == null ? OrderParam.OrderBy.ASC : param.getOrderType();
                orderSpecs.add(new OrderSpec(provider, dir));
            }
        }
        return this;
    }

    public Query<T> limit(int n) {
        return limit(0, n);
    }

    public Query<T> limit(int offset, int n) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (n <= 0) {
            throw new IllegalArgumentException("limit count must be > 0: " + n);
        }
        this.offset = offset;
        this.limit = n;
        return this;
    }

    public Query<T> distinct() {
        this.distinct = true;
        return this;
    }

    /**
     * 应用外部查询扩展（例如动态过滤条件适配器 {@code PageQueryParams.of(param)}）。
     */
    public Query<T> apply(QueryCustomizer customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        customizer.customize(this);
        return this;
    }

    @Override
    public String resolveColumn(String fieldOrColumn) {
        return renderContext().resolveIdentifier(fieldOrColumn);
    }

    // ===== 2. 投影切换（进入只含终结方法的 Select1..8 / SelectN） =====

    public <E1, A> Select1<A> select(IGetter<E1, A> c1) {
        return new Select1<>(this, c1);
    }

    public <E1, A, E2, B> Select2<A, B> select(IGetter<E1, A> c1, IGetter<E2, B> c2) {
        return new Select2<>(this, c1, c2);
    }

    public <E1, A, E2, B, E3, C> Select3<A, B, C> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3) {
        return new Select3<>(this, c1, c2, c3);
    }

    public <E1, A, E2, B, E3, C, E4, D> Select4<A, B, C, D> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4) {
        return new Select4<>(this, c1, c2, c3, c4);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E> Select5<A, B, C, D, E> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4, IGetter<E5, E> c5) {
        return new Select5<>(this, c1, c2, c3, c4, c5);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F> Select6<A, B, C, D, E, F> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3,
            IGetter<E4, D> c4, IGetter<E5, E> c5, IGetter<E6, F> c6) {
        return new Select6<>(this, c1, c2, c3, c4, c5, c6);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F, E7, G> Select7<A, B, C, D, E, F, G> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3,
            IGetter<E4, D> c4, IGetter<E5, E> c5, IGetter<E6, F> c6, IGetter<E7, G> c7) {
        return new Select7<>(this, c1, c2, c3, c4, c5, c6, c7);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F, E7, G, E8, H> Select8<A, B, C, D, E, F, G, H> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4,
            IGetter<E5, E> c5, IGetter<E6, F> c6, IGetter<E7, G> c7, IGetter<E8, H> c8) {
        return new Select8<>(this, c1, c2, c3, c4, c5, c6, c7, c8);
    }

    @SafeVarargs
    public final <E> SelectN select(IGetter<E, ?>... columns) {
        if (columns == null || columns.length == 0) {
            throw new IllegalArgumentException("select columns must not be empty");
        }
        List<IGetter<?, ?>> list = new ArrayList<>(columns.length);
        for (IGetter<E, ?> col : columns) {
            list.add(Objects.requireNonNull(col, "select column must not be null"));
        }
        return new SelectN(this, list);
    }

    // ===== 3. 终结方法（未写 select 时直接可用） =====

    /** 查询主表实体列表（联表时只查主表列）。 */
    public List<T> list() {
        RenderContext ctx = renderContext();
        return executeList(Projections.entity(rootType, ctx), ctx);
    }

    /** 查询并按目标类型字段名自动推列映射。 */
    public <V> List<V> list(Class<V> type) {
        RenderContext ctx = renderContext();
        return executeList(Projections.auto(type, ctx), ctx);
    }

    /** 严格单行查询：0 行返回 empty，多于 1 行抛 {@link IllegalStateException}。 */
    public Optional<T> one() {
        RenderContext ctx = renderContext();
        return executeOne(Projections.entity(rootType, ctx), ctx);
    }

    public <V> Optional<V> one(Class<V> type) {
        RenderContext ctx = renderContext();
        return executeOne(Projections.auto(type, ctx), ctx);
    }

    /** 取首行（自动应用 LIMIT 1）。 */
    public Optional<T> first() {
        RenderContext ctx = renderContext();
        return executeFirst(Projections.entity(rootType, ctx), ctx);
    }

    public <V> Optional<V> first(Class<V> type) {
        RenderContext ctx = renderContext();
        return executeFirst(Projections.auto(type, ctx), ctx);
    }

    /** 流式读取（调用方必须使用 try-with-resources 关闭返回的 Stream）。 */
    public Stream<T> stream() {
        RenderContext ctx = renderContext();
        return executeStream(Projections.entity(rootType, ctx), ctx);
    }

    public <V> Stream<V> stream(Class<V> type) {
        RenderContext ctx = renderContext();
        return executeStream(Projections.auto(type, ctx), ctx);
    }

    /** 分页查询（自动执行 COUNT + 方言分页）。 */
    public PageResult<T> page(PageRequest page) {
        RenderContext ctx = renderContext();
        return executePage(page, Projections.entity(rootType, ctx), ctx);
    }

    public <V> PageResult<V> page(PageRequest page, Class<V> type) {
        RenderContext ctx = renderContext();
        return executePage(page, Projections.auto(type, ctx), ctx);
    }

    // ===== 4. 聚合终结方法 =====

    public long count() {
        RenderContext ctx = renderContext();
        List<String> cols = distinct ? Projections.entity(rootType, ctx).columns() : List.of("1");
        SqlStatement countStmt = renderCountStatement(cols, ctx);
        Long total = requireTemplate().query(countStmt, new SingleColumnRowMapper<>(Long.class));
        return total == null ? 0L : total;
    }

    public boolean exists() {
        RenderContext ctx = renderContext();
        SqlStatement stmt = renderStatement(List.of("1"), false, ctx);
        long start = offset == null ? 0L : offset;
        List<Integer> rows = requireTemplate().queryLimit(stmt, new SingleColumnRowMapper<>(Integer.class), start, 1L);
        return !rows.isEmpty();
    }

    public <E, R> Optional<R> max(IGetter<E, R> column) {
        return select(Exprs.max(column)).one();
    }

    public <E, R> Optional<R> min(IGetter<E, R> column) {
        return select(Exprs.min(column)).one();
    }

    public <E, N extends Number> Optional<N> sum(IGetter<E, N> column) {
        return select(Exprs.sum(column)).one();
    }

    public <E> Optional<Double> avg(IGetter<E, ? extends Number> column) {
        return select(Exprs.avg(column)).one();
    }

    /**
     * 渲染为不可变 {@link SqlStatement}（默认查询主表全部列）。
     */
    public SqlStatement toSqlStatement() {
        RenderContext ctx = renderContext();
        return renderStatement(Projections.entity(rootType, ctx).columns(), true, ctx);
    }

    public SqlStatement toSqlStatement(Class<?> type) {
        RenderContext ctx = renderContext();
        return renderStatement(Projections.auto(type, ctx).columns(), true, ctx);
    }

    // ===== 内部执行与渲染 =====

    <V> List<V> executeList(Projection<V> projection) {
        return executeList(projection, renderContext());
    }

    private <V> List<V> executeList(Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderStatement(projection.columns(), true, ctx);
        if (limit != null) {
            long start = offset == null ? 0L : offset;
            return requireTemplate().queryLimit(stmt, projection.mapper(), start, limit);
        }
        return requireTemplate().queryList(stmt, projection.mapper());
    }

    <V> Optional<V> executeOne(Projection<V> projection) {
        return executeOne(projection, renderContext());
    }

    private <V> Optional<V> executeOne(Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderStatement(projection.columns(), true, ctx);
        if (limit != null) {
            long start = offset == null ? 0L : offset;
            List<V> rows = requireTemplate().queryLimit(stmt, projection.mapper(), start, Math.min(limit, 2));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            if (limit > 1 && rows.size() > 1) {
                throw new IllegalStateException("Expected at most 1 row, but query returned multiple rows: " + stmt.sql());
            }
            return Optional.ofNullable(rows.get(0));
        }
        return Optional.ofNullable(requireTemplate().queryOne(stmt, projection.mapper()));
    }

    <V> Optional<V> executeFirst(Projection<V> projection) {
        return executeFirst(projection, renderContext());
    }

    private <V> Optional<V> executeFirst(Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderStatement(projection.columns(), true, ctx);
        long start = offset == null ? 0L : offset;
        List<V> rows = requireTemplate().queryLimit(stmt, projection.mapper(), start, 1L);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    <V> Stream<V> executeStream(Projection<V> projection) {
        return executeStream(projection, renderContext());
    }

    private <V> Stream<V> executeStream(Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderStatement(projection.columns(), true, ctx);
        if (limit != null) {
            long start = offset == null ? 0L : offset;
            return requireTemplate().queryStream(stmt, projection.mapper(), start, limit);
        }
        return requireTemplate().queryStream(stmt, projection.mapper());
    }

    <V> PageResult<V> executePage(PageRequest page, Projection<V> projection) {
        return executePage(page, projection, renderContext());
    }

    private <V> PageResult<V> executePage(PageRequest page, Projection<V> projection, RenderContext ctx) {
        Objects.requireNonNull(page, "page must not be null");
        SqlStatement dataStmt = renderStatement(projection.columns(), true, ctx);
        SqlStatement countStmt = renderCountStatement(projection.columns(), ctx);
        return requireTemplate().queryPage(dataStmt, countStmt, projection.mapper(), page);
    }

    SqlStatement renderStatement(List<String> selectColumns) {
        return renderStatement(selectColumns, true, renderContext());
    }

    private SqlStatement renderStatement(List<String> selectColumns, boolean includeOrderBy, RenderContext ctx) {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (distinct) {
            sql.append("DISTINCT ");
        }
        sql.append(String.join(", ", selectColumns));
        sql.append(" FROM ").append(ctx.renderFromClause());

        SqlParameterList params = new SqlParameterList();
        if (!whereCriterion.isEmpty()) {
            StringBuilder whereSql = new StringBuilder();
            whereCriterion.render(ctx, whereSql, params, ParameterSource.WHERE);
            if (!whereSql.isEmpty()) {
                sql.append(" WHERE ").append(whereSql);
            }
        }
        if (!groupByColumns.isEmpty()) {
            List<String> groups = new ArrayList<>(groupByColumns.size());
            for (ColumnMetaProvider col : groupByColumns) {
                groups.add(col.resolveSql(ctx));
            }
            sql.append(" GROUP BY ").append(String.join(", ", groups));
        }
        if (!havingCriterion.isEmpty()) {
            StringBuilder havingSql = new StringBuilder();
            havingCriterion.render(ctx, havingSql, params, ParameterSource.WHERE);
            if (!havingSql.isEmpty()) {
                sql.append(" HAVING ").append(havingSql);
            }
        }
        if (includeOrderBy && !orderSpecs.isEmpty()) {
            List<String> orders = new ArrayList<>(orderSpecs.size());
            for (OrderSpec spec : orderSpecs) {
                orders.add(spec.column().resolveSql(ctx) + " " + spec.direction().name());
            }
            sql.append(" ORDER BY ").append(String.join(", ", orders));
        }

        StatementMetadata meta = StatementMetadata.builder(Operation.SELECT)
                .tables(ctx.extractTables())
                .entityType(rootType)
                .build();
        return SqlStatement.of(sql.toString(), params.toList(), meta);
    }

    private SqlStatement renderCountStatement(List<String> selectColumns, RenderContext ctx) {
        if (!distinct && groupByColumns.isEmpty() && havingCriterion.isEmpty()) {
            return renderStatement(List.of("COUNT(*)"), false, ctx);
        }
        SqlStatement inner = renderStatement(distinct ? selectColumns : List.of("1"), false, ctx);
        return inner.withSql("SELECT COUNT(*) FROM (" + inner.sql() + ") TEMP_T");
    }

    private JoyaTemplate requireTemplate() {
        if (template == null) {
            throw new IllegalStateException(
                    "Query was created offline via Query.of(...) and is not bound to a JoyaClient or Tx");
        }
        return template;
    }
}
