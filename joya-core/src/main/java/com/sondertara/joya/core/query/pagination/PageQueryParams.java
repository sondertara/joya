package com.sondertara.joya.core.query.pagination;

import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.QueryCustomizer;
import com.sondertara.joya.exceptions.TaraException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 动态筛选协议 {@link PageQueryParam} 到查询内核 {@link QueryCustomizer} 的适配器。
 *
 * <p>用法：
 * <pre>{@code
 * PageResult<User> page = joya.from(User.class)
 *     .apply(PageQueryParams.of(param))
 *     .page(param.toPageRequest());
 * }</pre>
 *
 * <p>安全保证：所有外部传入的字段名与排序字段均通过 {@link com.sondertara.joya.core.query.ColumnMetaProvider#fromName(String)}
 * 及 {@link com.sondertara.joya.core.query.RenderContext} 校验是否在当前查询的 {@code from / join} 实体元数据白名单内，
 * 非法或未知字段直接抛出 {@link IllegalArgumentException}。
 */
public final class PageQueryParams {

    private PageQueryParams() {
    }

    public static QueryCustomizer of(PageQueryParam param) {
        Objects.requireNonNull(param, "param must not be null");
        return scope -> {
            List<SearchParam> params = param.getParams();
            PageQueryParam.Nested nested = param.getNested();
            boolean hasParams = params != null && !params.isEmpty();
            boolean hasNested = nested != null
                    && ((nested.getAnd() != null && !nested.getAnd().isEmpty())
                    || (nested.getOr() != null && !nested.getOr().isEmpty()));
            if (hasParams || hasNested) {
                scope.where(w -> {
                    if (hasParams) {
                        if (param.getRootOperator() == LinkType.OR) {
                            w.orNested(sub -> applySearchParams(sub, params));
                        } else {
                            applySearchParams(w, params);
                        }
                    }
                    if (hasNested) {
                        if (nested.getAnd() != null && !nested.getAnd().isEmpty()) {
                            w.andNested(sub -> applySearchParams(sub, nested.getAnd()));
                        }
                        if (nested.getOr() != null && !nested.getOr().isEmpty()) {
                            w.orNested(sub -> applySearchParams(sub, nested.getOr()));
                        }
                    }
                    return w;
                });
            }
            List<OrderParam> orderList = param.getOrderList();
            if (orderList != null && !orderList.isEmpty()) {
                scope.orderBy(orderList.toArray(new OrderParam[0]));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static WhereCriterion applySearchParams(WhereCriterion w, List<SearchParam> searchParams) {
        for (SearchParam sp : searchParams) {
            if (sp == null) {
                continue;
            }
            String fieldName = sp.getFieldName();
            Object val = sp.getFieldValue();
            FieldParam.Operator op = sp.getOperator() == null ? FieldParam.Operator.EQ : sp.getOperator();
            switch (op) {
                case EQ -> w.eqIfPresent(fieldName, val);
                case NEQ -> w.neIfPresent(fieldName, val);
                case GT -> w.gtIfPresent(fieldName, val);
                case GTE -> w.gteIfPresent(fieldName, val);
                case LT -> w.ltIfPresent(fieldName, val);
                case LTE -> w.lteIfPresent(fieldName, val);
                case IN -> {
                    if (val != null && val.getClass().isArray()) {
                        w.inIfNotEmpty(fieldName, Arrays.asList((Object[]) val));
                    } else if (val instanceof Collection<?> col) {
                        w.inIfNotEmpty(fieldName, (Collection<Object>) col);
                    } else if (val != null) {
                        throw new TaraException("The value of operator [IN] must be collection");
                    }
                }
                case LIKE -> w.containsIfNotBlank(fieldName, val);
                case LIKE_L -> w.endsWithIfNotBlank(fieldName, val);
                case LIKE_R -> w.startsWithIfNotBlank(fieldName, val);
            }
        }
        return w;
    }
}
