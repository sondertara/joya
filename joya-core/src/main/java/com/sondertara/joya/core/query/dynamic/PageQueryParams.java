package com.sondertara.joya.core.query.dynamic;

import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.QueryCustomizer;
import com.sondertara.joya.core.query.pagination.FilterCondition;
import com.sondertara.joya.core.query.pagination.FilterOperator;
import com.sondertara.joya.core.query.pagination.PageQueryRequest;
import com.sondertara.joya.core.query.pagination.SortCondition;
import com.sondertara.joya.exceptions.TaraException;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 动态筛选协议 {@link PageQueryRequest} 到查询内核 {@link QueryCustomizer} 的适配器。
 *
 * <p>用法：
 * <pre>{@code
 * PageResult<User> page = joya.from(User.class)
 *     .apply(PageQueryParams.of(request))
 *     .page(request.toPageRequest());
 * }</pre>
 *
 * <p>安全保证：所有外部传入的字段名与排序字段均通过 {@link com.sondertara.joya.core.query.ColumnMetaProvider#fromName(String)}
 * 及 {@link com.sondertara.joya.core.query.RenderContext} 校验是否在当前查询的 {@code from / join} 实体元数据白名单内，
 * 非法或未知字段直接抛出 {@link IllegalArgumentException}。
 */
public final class PageQueryParams {

    private PageQueryParams() {
    }

    public static QueryCustomizer of(PageQueryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return scope -> {
            List<FilterCondition> filters = request.getFilters();
            PageQueryRequest.Nested nested = request.getNested();
            boolean hasFilters = filters != null && !filters.isEmpty();
            boolean hasNested = nested != null
                    && ((nested.getAnd() != null && !nested.getAnd().isEmpty())
                    || (nested.getOr() != null && !nested.getOr().isEmpty()));
            if (hasFilters || hasNested) {
                scope.where(w -> {
                    if (hasFilters) {
                        if (request.getRootOperator() == LinkType.OR) {
                            w.orNested(sub -> applyFilters(sub, filters));
                        } else {
                            applyFilters(w, filters);
                        }
                    }
                    if (hasNested) {
                        if (nested.getAnd() != null && !nested.getAnd().isEmpty()) {
                            w.andNested(sub -> applyFilters(sub, nested.getAnd()));
                        }
                        if (nested.getOr() != null && !nested.getOr().isEmpty()) {
                            w.orNested(sub -> applyFilters(sub, nested.getOr()));
                        }
                    }
                    return w;
                });
            }
            List<SortCondition> sorts = request.getSorts();
            if (sorts != null && !sorts.isEmpty()) {
                scope.orderBy(sorts.toArray(new SortCondition[0]));
            }
        };
    }

    private static WhereCriterion applyFilters(WhereCriterion w, List<FilterCondition> filters) {
        for (FilterCondition filter : filters) {
            if (filter == null) {
                continue;
            }
            String field = filter.getField();
            Object val = filter.getValue();
            FilterOperator op = filter.getOperator() == null ? FilterOperator.EQ : filter.getOperator();
            switch (op) {
                case EQ -> w.eqIfPresent(field, val);
                case NE -> w.neIfPresent(field, val);
                case GT -> w.gtIfPresent(field, val);
                case GE -> w.gteIfPresent(field, val);
                case LT -> w.ltIfPresent(field, val);
                case LE -> w.lteIfPresent(field, val);
                case LIKE -> w.containsIfNotBlank(field, val);
                case LIKE_LEFT -> w.endsWithIfNotBlank(field, val);
                case LIKE_RIGHT -> w.startsWithIfNotBlank(field, val);
                case IN -> w.inIfNotEmpty(field, extractCollection("IN", val));
                case NOT_IN -> w.notInIfNotEmpty(field, extractCollection("NOT_IN", val));
                case BETWEEN -> {
                    List<Object> range = extractCollection("BETWEEN", val);
                    if (range != null && !range.isEmpty()) {
                        if (range.size() != 2) {
                            throw new TaraException("The value of operator [BETWEEN] must contain 2 elements");
                        }
                        w.betweenIfPresent(field, range.get(0), range.get(1));
                    }
                }
                case IS_NULL -> w.isNull(field);
                case IS_NOT_NULL -> w.isNotNull(field);
            }
        }
        return w;
    }

    private static List<Object> extractCollection(String opName, Object val) {
        if (val == null) {
            return List.of();
        }
        if (val.getClass().isArray()) {
            int length = Array.getLength(val);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(val, i));
            }
            return values;
        }
        if (val instanceof Collection<?> col) {
            return new ArrayList<>(col);
        }
        throw new TaraException("The value of operator [" + opName + "] must be collection or array");
    }
}
