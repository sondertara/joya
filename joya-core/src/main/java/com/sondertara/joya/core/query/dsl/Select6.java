package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 6 列投影句柄：支持 {@code list(X.class)} 按名字映射或 {@code list(X::new)} 按位置构造映射。
 */
public final class Select6<A, B, C, D, E, F> extends SelectN {

    Select6(Query<?> query, IGetter<?, A> c1, IGetter<?, B> c2, IGetter<?, C> c3,
            IGetter<?, D> c4, IGetter<?, E> c5, IGetter<?, F> c6) {
        super(query, List.of(c1, c2, c3, c4, c5, c6));
    }

    public <V> List<V> list(Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper) {
        return query.executeList(positional(mapper));
    }

    public <V> Optional<V> one(Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper) {
        return query.executeOne(positional(mapper));
    }

    public <V> Optional<V> first(Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper) {
        return query.executeFirst(positional(mapper));
    }

    public <V> Stream<V> stream(Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper) {
        return query.executeStream(positional(mapper));
    }

    public <V> PageResult<V> page(Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper, PageRequest page) {
        return query.executePage(page, positional(mapper));
    }

    @SuppressWarnings("unchecked")
    private <V> Projection<V> positional(
            Mapper6<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? extends V> mapper) {
        return positionalProjection(
                args -> mapper.apply((A) args[0], (B) args[1], (C) args[2], (D) args[3], (E) args[4], (F) args[5]),
                mapper);
    }
}
