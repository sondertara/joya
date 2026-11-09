package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.LockMode;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.ColumnMetaProvider;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.query.pagination.SortCondition;
import com.sondertara.joya.core.query.pagination.SortDirection;

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

    private final QuerySpec<T> spec;
    private final QueryRenderer<T> renderer;
    private final QueryExecutor<T> executor;

    public Query(ExecutionKernel kernel, Class<T> rootType) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        this.spec = new QuerySpec<>(rootType);
        this.renderer = new QueryRenderer<>(kernel);
        this.executor = new QueryExecutor<>(kernel, renderer);
    }

    private Query(QuerySpec<T> spec, QueryRenderer<T> renderer, QueryExecutor<T> executor) {
        this.spec = spec;
        this.renderer = renderer;
        this.executor = executor;
    }

    private Query<T> fork() {
        return new Query<>(spec.copy(), renderer, executor);
    }

    /**
     * 离线创建查询构建器（不绑定数据源，仅用于构造与检视 {@link #toSqlStatement()}）。
     */
    public static <T> Query<T> of(Class<T> rootType) {
        return new Query<>(null, rootType);
    }

    @Override
    public Class<T> rootType() {
        return spec.rootType();
    }

    RenderContext renderContext() {
        return renderer.renderContext(spec);
    }

    // ===== 1. 行级子句（全部返回 Query<T>，可任意顺序组合） =====

    public <L, R> Query<T> join(IGetter<L, ?> left, IGetter<R, ?> right) {
        spec.joinCriterion().join(left, right);
        return this;
    }

    public <L, R> Query<T> join(IGetter<L, ?> left, IGetter<R, ?> right, String alias) {
        spec.joinCriterion().join(left, right, alias);
        return this;
    }

    public <L, R> Query<T> join(IGetter<L, ?> left, IGetter<R, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().join(left, right, onCondition);
        return this;
    }

    public <L, R> Query<T> join(
            IGetter<L, ?> left, IGetter<R, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().join(left, right, alias, onCondition);
        return this;
    }

    public <L, R> Query<T> leftJoin(IGetter<L, ?> left, IGetter<R, ?> right) {
        spec.joinCriterion().leftJoin(left, right);
        return this;
    }

    public <L, R> Query<T> leftJoin(IGetter<L, ?> left, IGetter<R, ?> right, String alias) {
        spec.joinCriterion().leftJoin(left, right, alias);
        return this;
    }

    public <L, R> Query<T> leftJoin(IGetter<L, ?> left, IGetter<R, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().leftJoin(left, right, onCondition);
        return this;
    }

    public <L, R> Query<T> leftJoin(
            IGetter<L, ?> left, IGetter<R, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().leftJoin(left, right, alias, onCondition);
        return this;
    }

    public <L, R> Query<T> rightJoin(IGetter<L, ?> left, IGetter<R, ?> right) {
        spec.joinCriterion().rightJoin(left, right);
        return this;
    }

    public <L, R> Query<T> rightJoin(IGetter<L, ?> left, IGetter<R, ?> right, String alias) {
        spec.joinCriterion().rightJoin(left, right, alias);
        return this;
    }

    public <L, R> Query<T> rightJoin(IGetter<L, ?> left, IGetter<R, ?> right, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().rightJoin(left, right, onCondition);
        return this;
    }

    public <L, R> Query<T> rightJoin(
            IGetter<L, ?> left, IGetter<R, ?> right, String alias, UnaryOperator<WhereCriterion> onCondition) {
        spec.joinCriterion().rightJoin(left, right, alias, onCondition);
        return this;
    }

    @Override
    public Query<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        spec.whereCriterion().and(condition);
        return this;
    }

    public Query<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        if (!criterion.isEmpty()) {
            spec.whereCriterion().and(w -> criterion.copy());
        }
        return this;
    }

    public <E1> Query<T> groupBy(IGetter<E1, ?> c1) {
        spec.groupByColumns().add(ColumnMetaProvider.fromGetter(Objects.requireNonNull(c1)));
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

    public <E1, E2, E3, E4, E5> Query<T> groupBy(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4, IGetter<E5, ?> c5) {
        return groupBy(c1, c2, c3, c4).groupBy(c5);
    }

    public <E1, E2, E3, E4, E5, E6> Query<T> groupBy(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3,
            IGetter<E4, ?> c4, IGetter<E5, ?> c5, IGetter<E6, ?> c6) {
        return groupBy(c1, c2, c3, c4).groupBy(c5, c6);
    }

    public <E1, E2, E3, E4, E5, E6, E7> Query<T> groupBy(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4,
            IGetter<E5, ?> c5, IGetter<E6, ?> c6, IGetter<E7, ?> c7) {
        return groupBy(c1, c2, c3, c4).groupBy(c5, c6, c7);
    }

    public <E1, E2, E3, E4, E5, E6, E7, E8> Query<T> groupBy(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4,
            IGetter<E5, ?> c5, IGetter<E6, ?> c6, IGetter<E7, ?> c7, IGetter<E8, ?> c8) {
        return groupBy(c1, c2, c3, c4).groupBy(c5, c6, c7, c8);
    }

    @SafeVarargs
    public final <E> Query<T> groupBy(IGetter<E, ?>... columns) {
        if (columns != null) {
            for (IGetter<E, ?> column : columns) {
                if (column != null) {
                    spec.groupByColumns().add(ColumnMetaProvider.fromGetter(column));
                }
            }
        }
        return this;
    }

    public Query<T> having(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "having condition must not be null");
        spec.havingCriterion().and(condition);
        return this;
    }

    public <E> Query<T> orderByAsc(IGetter<E, ?> column) {
        return orderBy(column, SortDirection.ASC);
    }

    public <E> Query<T> orderByDesc(IGetter<E, ?> column) {
        return orderBy(column, SortDirection.DESC);
    }

    public <E> Query<T> orderBy(IGetter<E, ?> column, SortDirection direction) {
        Objects.requireNonNull(column, "orderBy column must not be null");
        Objects.requireNonNull(direction, "orderBy direction must not be null");
        spec.orderSpecs().add(new QuerySpec.OrderSpec(ColumnMetaProvider.fromGetter(column), direction));
        return this;
    }

    @Override
    public Query<T> orderBy(SortCondition... sorts) {
        if (sorts != null) {
            for (SortCondition sort : sorts) {
                if (sort == null) {
                    continue;
                }
                ColumnMetaProvider provider = ColumnMetaProvider.fromName(sort.getField());
                SortDirection dir = sort.getDirection() == null ? SortDirection.ASC : sort.getDirection();
                spec.orderSpecs().add(new QuerySpec.OrderSpec(provider, dir));
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
        spec.limit(offset, n);
        return this;
    }

    public Query<T> distinct() {
        spec.distinct(true);
        return this;
    }

    /**
     * 追加悲观排他行锁（{@code FOR UPDATE}）。
     */
    public Query<T> forUpdate() {
        spec.lockMode(LockMode.FOR_UPDATE);
        return this;
    }

    /**
     * 追加不等待悲观排他行锁（{@code FOR UPDATE NOWAIT}）。
     */
    public Query<T> forUpdateNoWait() {
        spec.lockMode(LockMode.FOR_UPDATE_NOWAIT);
        return this;
    }

    /**
     * 追加跳过已锁定行的悲观排他行锁（{@code FOR UPDATE SKIP LOCKED}）。
     */
    public Query<T> forUpdateSkipLocked() {
        spec.lockMode(LockMode.FOR_UPDATE_SKIP_LOCKED);
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
        return new Select1<>(fork(), c1);
    }

    public <E1, A, E2, B> Select2<A, B> select(IGetter<E1, A> c1, IGetter<E2, B> c2) {
        return new Select2<>(fork(), c1, c2);
    }

    public <E1, A, E2, B, E3, C> Select3<A, B, C> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3) {
        return new Select3<>(fork(), c1, c2, c3);
    }

    public <E1, A, E2, B, E3, C, E4, D> Select4<A, B, C, D> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4) {
        return new Select4<>(fork(), c1, c2, c3, c4);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E> Select5<A, B, C, D, E> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4, IGetter<E5, E> c5) {
        return new Select5<>(fork(), c1, c2, c3, c4, c5);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F> Select6<A, B, C, D, E, F> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3,
            IGetter<E4, D> c4, IGetter<E5, E> c5, IGetter<E6, F> c6) {
        return new Select6<>(fork(), c1, c2, c3, c4, c5, c6);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F, E7, G> Select7<A, B, C, D, E, F, G> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3,
            IGetter<E4, D> c4, IGetter<E5, E> c5, IGetter<E6, F> c6, IGetter<E7, G> c7) {
        return new Select7<>(fork(), c1, c2, c3, c4, c5, c6, c7);
    }

    public <E1, A, E2, B, E3, C, E4, D, E5, E, E6, F, E7, G, E8, H> Select8<A, B, C, D, E, F, G, H> select(
            IGetter<E1, A> c1, IGetter<E2, B> c2, IGetter<E3, C> c3, IGetter<E4, D> c4,
            IGetter<E5, E> c5, IGetter<E6, F> c6, IGetter<E7, G> c7, IGetter<E8, H> c8) {
        return new Select8<>(fork(), c1, c2, c3, c4, c5, c6, c7, c8);
    }

    public <E1, E2, E3, E4, E5, E6, E7, E8, E9> SelectN select(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4, IGetter<E5, ?> c5,
            IGetter<E6, ?> c6, IGetter<E7, ?> c7, IGetter<E8, ?> c8, IGetter<E9, ?> c9) {
        return new SelectN(fork(), List.of(c1, c2, c3, c4, c5, c6, c7, c8, c9));
    }

    public <E1, E2, E3, E4, E5, E6, E7, E8, E9, E10> SelectN select(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4, IGetter<E5, ?> c5,
            IGetter<E6, ?> c6, IGetter<E7, ?> c7, IGetter<E8, ?> c8, IGetter<E9, ?> c9, IGetter<E10, ?> c10) {
        return new SelectN(fork(), List.of(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10));
    }

    public <E1, E2, E3, E4, E5, E6, E7, E8, E9, E10, E11> SelectN select(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4, IGetter<E5, ?> c5,
            IGetter<E6, ?> c6, IGetter<E7, ?> c7, IGetter<E8, ?> c8, IGetter<E9, ?> c9, IGetter<E10, ?> c10,
            IGetter<E11, ?> c11) {
        return new SelectN(fork(), List.of(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11));
    }

    public <E1, E2, E3, E4, E5, E6, E7, E8, E9, E10, E11, E12> SelectN select(
            IGetter<E1, ?> c1, IGetter<E2, ?> c2, IGetter<E3, ?> c3, IGetter<E4, ?> c4, IGetter<E5, ?> c5,
            IGetter<E6, ?> c6, IGetter<E7, ?> c7, IGetter<E8, ?> c8, IGetter<E9, ?> c9, IGetter<E10, ?> c10,
            IGetter<E11, ?> c11, IGetter<E12, ?> c12) {
        return new SelectN(fork(), List.of(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12));
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
        return new SelectN(fork(), list);
    }

    // ===== 3. 终结方法（未写 select 时直接可用） =====

    /** 查询主表实体列表（联表时只查主表列）。 */
    public List<T> list() {
        return executor.executeList(spec, Projections.entity(rootType(), renderContext()));
    }

    /** 查询并按目标类型字段名自动推列映射。 */
    public <V> List<V> list(Class<V> type) {
        return executor.executeList(spec, Projections.auto(type, renderContext()));
    }

    /** 严格单行查询：0 行返回 empty，多于 1 行抛 {@link IllegalStateException}。 */
    public Optional<T> one() {
        return executor.executeOne(spec, Projections.entity(rootType(), renderContext()));
    }

    public <V> Optional<V> one(Class<V> type) {
        return executor.executeOne(spec, Projections.auto(type, renderContext()));
    }

    /** 取首行（自动应用 LIMIT 1）。 */
    public Optional<T> first() {
        return executor.executeFirst(spec, Projections.entity(rootType(), renderContext()));
    }

    public <V> Optional<V> first(Class<V> type) {
        return executor.executeFirst(spec, Projections.auto(type, renderContext()));
    }

    /** 流式读取（调用方必须使用 try-with-resources 关闭返回的 Stream）。 */
    public Stream<T> stream() {
        return executor.executeStream(spec, Projections.entity(rootType(), renderContext()));
    }

    public <V> Stream<V> stream(Class<V> type) {
        return executor.executeStream(spec, Projections.auto(type, renderContext()));
    }

    /** 分页查询（自动执行 COUNT + 方言分页）。 */
    public PageResult<T> page(PageRequest page) {
        return executor.executePage(spec, page, Projections.entity(rootType(), renderContext()));
    }

    public <V> PageResult<V> page(Class<V> type, PageRequest page) {
        return executor.executePage(spec, page, Projections.auto(type, renderContext()));
    }

    // ===== 4. 聚合终结方法 =====

    public long count() {
        RenderContext ctx = renderContext();
        List<SqlNode> nodes = spec.distinct()
                ? Projections.entity(rootType(), ctx).nodes()
                : List.of(SqlNodes.raw("1"));
        return executor.count(spec, nodes, false, ctx);
    }

    public boolean exists() {
        return executor.exists(spec);
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

    public <E, S extends Number> Optional<S> sum(IGetter<E, ? extends Number> column, Class<S> resultType) {
        return select(Exprs.sum(column, resultType)).one();
    }

    public <E> Optional<Long> sumAsLong(IGetter<E, ? extends Number> column) {
        return select(Exprs.sumAsLong(column)).one();
    }

    public <E> Optional<java.math.BigDecimal> sumAsDecimal(IGetter<E, ? extends Number> column) {
        return select(Exprs.sumAsDecimal(column)).one();
    }

    public <E> Optional<Double> avg(IGetter<E, ? extends Number> column) {
        return select(Exprs.avg(column)).one();
    }

    /** 渲染为不可变 {@link SqlStatement}（默认查询主表全部列）。 */
    public SqlStatement toSqlStatement() {
        return renderStatement(Projections.entity(rootType(), renderContext()));
    }

    public SqlStatement toSqlStatement(Class<?> type) {
        return renderStatement(Projections.auto(type, renderContext()));
    }

    // ===== 内部桥接：Select1..SelectN 使用 =====

    SqlStatement renderStatement(Projection<?> projection) {
        RenderContext ctx = projection.context() != null ? projection.context() : renderContext();
        return renderer.renderStatement(spec, projection.nodes(), true, ctx);
    }

    <V> List<V> executeList(Projection<V> projection) {
        return executor.executeList(spec, projection);
    }

    <V> Optional<V> executeOne(Projection<V> projection) {
        return executor.executeOne(spec, projection);
    }

    <V> Optional<V> executeFirst(Projection<V> projection) {
        return executor.executeFirst(spec, projection);
    }

    <V> Stream<V> executeStream(Projection<V> projection) {
        return executor.executeStream(spec, projection);
    }

    <V> PageResult<V> executePage(PageRequest page, Projection<V> projection) {
        return executor.executePage(spec, page, projection);
    }
}
