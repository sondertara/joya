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
 * 投影句柄公共基类与多列（超过 8 列）按名字映射句柄。
 */
public sealed class SelectN
        permits Select1, Select2, Select3, Select4, Select5, Select6, Select7, Select8 {

    final Query<?> query;
    final List<IGetter<?, ?>> columns;

    SelectN(Query<?> query, List<IGetter<?, ?>> columns) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
    }

    public <E> SelectN and(IGetter<E, ?> column) {
        Objects.requireNonNull(column, "column must not be null");
        java.util.List<IGetter<?, ?>> next = new java.util.ArrayList<>(columns.size() + 1);
        next.addAll(columns);
        next.add(column);
        return new SelectN(query, next);
    }

    public SqlStatement toSqlStatement() {
        return query.renderStatement(Projections.positional(columns, args -> null, null, query.renderContext()));
    }

    public final <V> List<V> list(Class<V> type) {
        return query.executeList(namedProjection(type));
    }

    public final <V> Optional<V> one(Class<V> type) {
        return query.executeOne(namedProjection(type));
    }

    public final <V> Optional<V> first(Class<V> type) {
        return query.executeFirst(namedProjection(type));
    }

    public final <V> Stream<V> stream(Class<V> type) {
        return query.executeStream(namedProjection(type));
    }

    public final <V> PageResult<V> page(Class<V> type, PageRequest page) {
        return query.executePage(page, namedProjection(type));
    }

    public final SqlStatement toSqlStatement(Class<?> type) {
        return query.renderStatement(namedProjection(type));
    }

    private <V> Projection<V> namedProjection(Class<V> type) {
        return Projections.named(columns, type, query.renderContext());
    }

    final <V> Projection<V> positionalProjection(Function<Object[], V> factory, Object mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return Projections.positional(columns, factory, mapper, query.renderContext());
    }
}
