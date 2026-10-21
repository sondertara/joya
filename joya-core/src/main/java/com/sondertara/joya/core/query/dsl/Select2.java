package com.sondertara.joya.core.query.dsl;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 2 列投影句柄：支持 {@code list(X.class)} 按名字映射或 {@code list(X::new)} 按位置构造映射。
 */
public final class Select2<A, B> {

    private final Query<?> query;
    private final List<IGetter<?, ?>> columns;

    Select2(Query<?> query, IGetter<?, A> c1, IGetter<?, B> c2) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.columns = List.of(c1, c2);
    }

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

    public <V> List<V> list(Mapper2<? super A, ? super B, ? extends V> mapper) {
        return query.executeList(positionalProjection(mapper));
    }

    public <V> Optional<V> one(Mapper2<? super A, ? super B, ? extends V> mapper) {
        return query.executeOne(positionalProjection(mapper));
    }

    public <V> Optional<V> first(Mapper2<? super A, ? super B, ? extends V> mapper) {
        return query.executeFirst(positionalProjection(mapper));
    }

    public <V> Stream<V> stream(Mapper2<? super A, ? super B, ? extends V> mapper) {
        return query.executeStream(positionalProjection(mapper));
    }

    public <V> PageResult<V> page(PageRequest page, Mapper2<? super A, ? super B, ? extends V> mapper) {
        return query.executePage(page, positionalProjection(mapper));
    }

    public SqlStatement toSqlStatement() {
        return query.renderStatement(positionalProjection((a, b) -> null).columns());
    }

    public SqlStatement toSqlStatement(Class<?> type) {
        return query.renderStatement(namedProjection(type).columns());
    }

    private <V> Projection<V> namedProjection(Class<V> type) {
        return Projections.named(columns, type, query.renderContext());
    }

    @SuppressWarnings("unchecked")
    private <V> Projection<V> positionalProjection(Mapper2<? super A, ? super B, ? extends V> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return Projections.positional(columns,
                args -> mapper.apply((A) args[0], (B) args[1]),
                mapper,
                query.renderContext());
    }
}
