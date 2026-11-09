package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.PageSlice;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.query.pagination.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 查询执行器：只负责执行 SqlStatement、分页数学、结果映射，不收集 DSL 子句、不拼 SQL。
 */
final class QueryExecutor<T> {

    private static final List<SqlNode> EXISTS_PROJECTION = List.of(SqlNodes.raw("1"));

    private final ExecutionKernel kernel;
    private final QueryRenderer<T> renderer;

    QueryExecutor(ExecutionKernel kernel, QueryRenderer<T> renderer) {
        this.kernel = kernel;
        this.renderer = renderer;
    }

    <V> List<V> executeList(QuerySpec<T> spec, Projection<V> projection) {
        return executeList(spec, projection, resolveContext(spec, projection));
    }

    private <V> List<V> executeList(QuerySpec<T> spec, Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderer.renderStatement(spec, projection.nodes(), true, ctx);
        if (spec.limit() != null) {
            long start = spec.offset() == null ? 0L : spec.offset();
            return requireKernel().queryLimit(stmt, mapper(projection), start, spec.limit());
        }
        return requireKernel().queryList(stmt, mapper(projection));
    }

    <V> Optional<V> executeOne(QuerySpec<T> spec, Projection<V> projection) {
        return executeOne(spec, projection, resolveContext(spec, projection));
    }

    private <V> Optional<V> executeOne(QuerySpec<T> spec, Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderer.renderStatement(spec, projection.nodes(), true, ctx);
        if (spec.limit() != null) {
            long start = spec.offset() == null ? 0L : spec.offset();
            List<V> rows = requireKernel().queryLimit(stmt, mapper(projection), start,
                    Math.min(spec.limit(), 2));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            if (spec.limit() > 1 && rows.size() > 1) {
                throw new IllegalStateException(
                        "Expected at most 1 row, but query returned multiple rows: " + stmt.sql());
            }
            return Optional.ofNullable(rows.get(0));
        }
        return Optional.ofNullable(requireKernel().queryOne(stmt, mapper(projection)));
    }

    <V> Optional<V> executeFirst(QuerySpec<T> spec, Projection<V> projection) {
        return executeFirst(spec, projection, resolveContext(spec, projection));
    }

    private <V> Optional<V> executeFirst(QuerySpec<T> spec, Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderer.renderStatement(spec, projection.nodes(), true, ctx);
        long start = spec.offset() == null ? 0L : spec.offset();
        List<V> rows = requireKernel().queryLimit(stmt, mapper(projection), start, 1L);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    <V> Stream<V> executeStream(QuerySpec<T> spec, Projection<V> projection) {
        return executeStream(spec, projection, resolveContext(spec, projection));
    }

    private <V> Stream<V> executeStream(QuerySpec<T> spec, Projection<V> projection, RenderContext ctx) {
        SqlStatement stmt = renderer.renderStatement(spec, projection.nodes(), true, ctx);
        if (spec.limit() != null) {
            long start = spec.offset() == null ? 0L : spec.offset();
            return requireKernel().queryStream(stmt, mapper(projection), start, spec.limit());
        }
        return requireKernel().queryStream(stmt, mapper(projection));
    }

    <V> PageResult<V> executePage(QuerySpec<T> spec, PageRequest page, Projection<V> projection) {
        return executePage(spec, page, projection, resolveContext(spec, projection));
    }

    private <V> PageResult<V> executePage(QuerySpec<T> spec, PageRequest page,
                                          Projection<V> projection, RenderContext ctx) {
        Objects.requireNonNull(page, "page must not be null");
        SqlStatement dataStmt = renderer.renderStatement(spec, projection.nodes(), true, ctx);
        if (!page.isNeedTotal()) {
            long baseOffset = spec.offset() == null ? 0L : spec.offset();
            long pageRelOffset = page.getOffset();
            long effectiveOffset = Math.addExact(baseOffset, pageRelOffset);
            long requested = (long) page.getSize() + 1L;
            long effectiveLimit = spec.limit() == null
                    ? requested
                    : Math.min(requested, Math.max(0L, spec.limit() - pageRelOffset));
            List<V> fetched = effectiveLimit <= 0L
                    ? List.of()
                    : requireKernel().queryLimit(dataStmt, mapper(projection), effectiveOffset, effectiveLimit);
            boolean hasNext = fetched.size() > page.getSize();
            List<V> rows = hasNext
                    ? new ArrayList<>(fetched.subList(0, page.getSize()))
                    : fetched;
            return PageResult.of(rows, -1L, page.getPageNum(), page.getSize(), hasNext);
        }
        if (spec.limit() != null || spec.offset() != null) {
            long total = count(spec, projection.nodes(), projection.aggregate(), ctx);
            long pageRelOffset = page.getOffset();
            if (total <= 0L || pageRelOffset >= total) {
                return PageResult.of(List.of(), total, page.getPageNum(), page.getSize());
            }
            long baseOffset = spec.offset() == null ? 0L : spec.offset();
            long effectiveOffset = Math.addExact(baseOffset, pageRelOffset);
            long effectiveLimit = spec.limit() == null
                    ? page.getSize()
                    : Math.min((long) page.getSize(), Math.max(0L, spec.limit() - pageRelOffset));
            List<V> rows = effectiveLimit <= 0L
                    ? List.of()
                    : requireKernel().queryLimit(dataStmt, mapper(projection), effectiveOffset, effectiveLimit);
            return PageResult.of(rows, total, page.getPageNum(), page.getSize());
        }
        SqlStatement countStmt = renderer.renderCountStatement(spec, projection.nodes(), projection.aggregate(), ctx);
        PageSlice<V> slice = requireKernel().queryPage(
                dataStmt, countStmt, mapper(projection), page.getOffset(), page.getSize());
        return PageResult.of(slice.rows(), slice.total(), page.getPageNum(), page.getSize());
    }

    long count(QuerySpec<T> spec, List<SqlNode> projectionNodes, boolean hasAggregate, RenderContext ctx) {
        if (spec.limit() != null && spec.limit() <= 0L) {
            return 0L;
        }
        SqlStatement countStmt = renderer.renderCountStatement(spec, projectionNodes, hasAggregate, ctx);
        Long total = requireKernel().query(countStmt, new SingleColumnRowMapper<>(Long.class));
        long rawTotal = total == null ? 0L : total;
        if (spec.offset() != null && spec.offset() > 0L) {
            rawTotal = Math.max(0L, rawTotal - spec.offset());
        }
        if (spec.limit() != null) {
            rawTotal = Math.min(rawTotal, spec.limit());
        }
        return rawTotal;
    }

    boolean exists(QuerySpec<T> spec) {
        if (spec.limit() != null && spec.limit() <= 0L) {
            return false;
        }
        RenderContext ctx = renderer.renderContext(spec);
        SqlStatement stmt = renderer.renderStatement(spec, EXISTS_PROJECTION,
                spec.offset() != null && spec.offset() > 0L, ctx);
        long start = spec.offset() == null ? 0L : spec.offset();
        List<Integer> rows = requireKernel().queryLimit(stmt,
                new SingleColumnRowMapper<>(Integer.class), start, 1L);
        return !rows.isEmpty();
    }

    private RenderContext resolveContext(QuerySpec<T> spec, Projection<?> projection) {
        return projection.context() != null ? projection.context() : renderer.renderContext(spec);
    }

    private <V> RowMapper<V> mapper(Projection<V> projection) {
        if (projection.mapper() != null) {
            return projection.mapper();
        }
        return requireKernel().mapperFor(projection.resultType(), projection.resultClass());
    }

    private ExecutionKernel requireKernel() {
        if (kernel == null) {
            throw new IllegalStateException(
                    "Query was created offline via Query.of(...) and is not bound to a JoyaClient or Tx");
        }
        return kernel;
    }
}
