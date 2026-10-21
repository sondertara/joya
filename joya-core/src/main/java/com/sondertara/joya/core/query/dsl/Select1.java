package com.sondertara.joya.core.query.dsl;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 单列投影句柄：可直接取值，也可按名字装入实体/DTO 或通过构造器/函数映射。
 *
 * @param <A> 第 1 列的 Java 类型
 */
public final class Select1<A> {

    private final Query<?> query;
    private final IGetter<?, A> c1;

    Select1(Query<?> query, IGetter<?, A> c1) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.c1 = Objects.requireNonNull(c1, "c1 must not be null");
    }

    // ===== 单列直接取值 =====

    public List<A> list() {
        return query.executeList(singleProjection());
    }

    public Optional<A> one() {
        return query.executeOne(singleProjection());
    }

    public Optional<A> first() {
        return query.executeFirst(singleProjection());
    }

    public Stream<A> stream() {
        return query.executeStream(singleProjection());
    }

    public PageResult<A> page(PageRequest page) {
        return query.executePage(page, singleProjection());
    }

    // ===== 按名字装入实体 / JavaBean =====

    public <V> List<V> list(Class<V> type) {
        return query.executeList(namedProjection(type));
    }

    public <V> Optional<V> one(Class<V> type) {
        return query.executeOne(namedProjection(type));
    }

    public <V> Optional<V> first(Class<V> type) {
        return query.executeFirst(namedProjection(type));
    }

    public <V> Stream<V> stream(Class<V> type) {
        return query.executeStream(namedProjection(type));
    }

    public <V> PageResult<V> page(PageRequest page, Class<V> type) {
        return query.executePage(page, namedProjection(type));
    }

    // ===== 按位置 / 函数映射 =====

    public <V> List<V> list(Function<? super A, ? extends V> mapper) {
        return query.executeList(positionalProjection(mapper));
    }

    public <V> Optional<V> one(Function<? super A, ? extends V> mapper) {
        return query.executeOne(positionalProjection(mapper));
    }

    public <V> Optional<V> first(Function<? super A, ? extends V> mapper) {
        return query.executeFirst(positionalProjection(mapper));
    }

    public <V> Stream<V> stream(Function<? super A, ? extends V> mapper) {
        return query.executeStream(positionalProjection(mapper));
    }

    public <V> PageResult<V> page(PageRequest page, Function<? super A, ? extends V> mapper) {
        return query.executePage(page, positionalProjection(mapper));
    }

    // ===== SQL 检视 =====

    public SqlStatement toSqlStatement() {
        return query.renderStatement(singleProjection().columns());
    }

    public SqlStatement toSqlStatement(Class<?> type) {
        return query.renderStatement(namedProjection(type).columns());
    }

    private Projection<A> singleProjection() {
        return Projections.single(c1, query.renderContext());
    }

    private <V> Projection<V> namedProjection(Class<V> type) {
        return Projections.named(List.of(c1), type, query.renderContext());
    }

    @SuppressWarnings("unchecked")
    private <V> Projection<V> positionalProjection(Function<? super A, ? extends V> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return Projections.positional(List.of(c1), args -> mapper.apply((A) args[0]), mapper, query.renderContext());
    }
}
