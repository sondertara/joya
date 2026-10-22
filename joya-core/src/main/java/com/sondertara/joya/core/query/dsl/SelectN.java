package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 多列（超过 8 列）按名字映射句柄。
 */
public final class SelectN {

    private final Query<?> query;
    private final List<IGetter<?, ?>> columns;

    SelectN(Query<?> query, List<IGetter<?, ?>> columns) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
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

    public SqlStatement toSqlStatement(Class<?> type) {
        return query.renderStatement(namedProjection(type).columns());
    }

    private <V> Projection<V> namedProjection(Class<V> type) {
        return Projections.named(columns, type, query.renderContext());
    }
}
