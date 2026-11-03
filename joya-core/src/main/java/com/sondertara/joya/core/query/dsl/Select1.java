package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
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
public final class Select1<A> extends SelectN {

    private final IGetter<?, A> c1;

    Select1(Query<?> query, IGetter<?, A> c1) {
        super(query, List.of(Objects.requireNonNull(c1, "c1 must not be null")));
        this.c1 = c1;
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

    // ===== 按位置 / 函数映射 =====

    public <V> List<V> list(Mapper1<? super A, ? extends V> mapper) {
        return query.executeList(positional(mapper));
    }

    public <V> Optional<V> one(Mapper1<? super A, ? extends V> mapper) {
        return query.executeOne(positional(mapper));
    }

    public <V> Optional<V> first(Mapper1<? super A, ? extends V> mapper) {
        return query.executeFirst(positional(mapper));
    }

    public <V> Stream<V> stream(Mapper1<? super A, ? extends V> mapper) {
        return query.executeStream(positional(mapper));
    }

    public <V> PageResult<V> page(Mapper1<? super A, ? extends V> mapper, PageRequest page) {
        return query.executePage(page, positional(mapper));
    }

    @Override
    public SqlStatement toSqlStatement() {
        return query.renderStatement(singleProjection());
    }

    private Projection<A> singleProjection() {
        return Projections.single(c1, query.renderContext());
    }

    @SuppressWarnings("unchecked")
    private <V> Projection<V> positional(Function<? super A, ? extends V> mapper) {
        return positionalProjection(args -> mapper.apply((A) args[0]), mapper);
    }
}
