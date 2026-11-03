package com.sondertara.joya.core.query.pagination;

import com.sondertara.joya.core.query.LinkType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通用动态分页查询请求协议：
 * <pre>{@code
 * {
 *   "pageNum": 1,
 *   "pageSize": 20,
 *   "needTotal": true,
 *   "sorts": [ { "field": "createTime", "direction": "DESC" } ],
 *   "filters": [ { "field": "status", "operator": "EQ", "value": 1 } ]
 * }
 * }</pre>
 *
 * <p>{@code filters} 与 {@code sorts} 的字段在通过 {@code PageQueryParams.of(request)} 应用到 {@code Query} 时，
 * 强制由 {@code RenderContext} 校验是否属于当前查询关联实体的合法列白名单，非法或未知字段一律拒绝。
 */
public class PageQueryRequest implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;

    /** 页码，1-based，默认 1 */
    private int pageNum = 1;

    /** 每页条数，默认 20 */
    private int pageSize = 20;

    /** 是否统计总数，默认 true */
    private boolean needTotal = true;

    /** 顶层过滤条件之间的连接方式，默认 AND */
    private LinkType rootOperator = LinkType.AND;

    /** 排序条件列表 */
    private List<SortCondition> sorts = new ArrayList<>();

    /** 过滤条件列表 */
    private List<FilterCondition> filters = new ArrayList<>();

    /** 可选嵌套条件组（AND / OR 子组） */
    private Nested nested;

    public PageQueryRequest() {
    }

    public PageQueryRequest(int pageNum, int pageSize) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    /** 页码归一化：小于 1 按 1 处理。 */
    public int normalizedPageNum() {
        return Math.max(pageNum, 1);
    }

    /** 页大小归一化：小于 1 按 1 处理。 */
    public int normalizedPageSize() {
        return Math.max(pageSize, 1);
    }

    /** 当前页之前需要跳过的记录数（64 位防溢出）。 */
    public long offset() {
        return (long) (normalizedPageNum() - 1) * (long) normalizedPageSize();
    }

    /**
     * 提取分页窗口坐标 {@link PageRequest}。
     */
    public PageRequest toPageRequest() {
        return PageRequest.ofOneBased(normalizedPageNum(), normalizedPageSize(), needTotal);
    }

    // ===== 流式构建与便捷操作 =====

    public PageQueryRequest pageNum(int pageNum) {
        this.pageNum = pageNum;
        return this;
    }

    public PageQueryRequest pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    public PageQueryRequest needTotal(boolean needTotal) {
        this.needTotal = needTotal;
        return this;
    }

    public PageQueryRequest rootOperator(LinkType rootOperator) {
        this.rootOperator = rootOperator == null ? LinkType.AND : rootOperator;
        return this;
    }

    public PageQueryRequest sorts(List<SortCondition> sorts) {
        this.sorts = sorts == null ? new ArrayList<>() : new ArrayList<>(sorts);
        return this;
    }

    public PageQueryRequest filters(List<FilterCondition> filters) {
        this.filters = filters == null ? new ArrayList<>() : new ArrayList<>(filters);
        return this;
    }

    public PageQueryRequest nested(Nested nested) {
        this.nested = nested;
        return this;
    }

    public PageQueryRequest addFilter(FilterCondition condition) {
        if (condition != null) {
            if (this.filters == null) {
                this.filters = new ArrayList<>();
            }
            this.filters.add(condition);
        }
        return this;
    }

    public PageQueryRequest addFilter(String field, FilterOperator operator, Object value) {
        if (this.filters == null) {
            this.filters = new ArrayList<>();
        }
        this.filters.add(new FilterCondition(field, operator, value));
        return this;
    }

    public boolean removeFilter(String field) {
        if (this.filters == null || field == null) {
            return false;
        }
        return this.filters.removeIf(f -> f != null && field.equalsIgnoreCase(f.getField()));
    }

    public FilterCondition getFilter(String field) {
        if (this.filters == null || field == null) {
            return null;
        }
        return this.filters.stream()
                .filter(f -> f != null && field.equalsIgnoreCase(f.getField()))
                .findFirst()
                .orElse(null);
    }

    public PageQueryRequest addSort(SortCondition condition) {
        if (condition != null) {
            if (this.sorts == null) {
                this.sorts = new ArrayList<>();
            }
            this.sorts.add(condition);
        }
        return this;
    }

    public PageQueryRequest addSort(String field, SortDirection direction) {
        if (this.sorts == null) {
            this.sorts = new ArrayList<>();
        }
        this.sorts.add(new SortCondition(field, direction));
        return this;
    }

    public PageQueryRequest addSortAsc(String field) {
        return addSort(field, SortDirection.ASC);
    }

    public PageQueryRequest addSortDesc(String field) {
        return addSort(field, SortDirection.DESC);
    }

    // ===== 标准 Getters / Setters =====

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isNeedTotal() {
        return needTotal;
    }

    public void setNeedTotal(boolean needTotal) {
        this.needTotal = needTotal;
    }

    public LinkType getRootOperator() {
        return rootOperator;
    }

    public void setRootOperator(LinkType rootOperator) {
        this.rootOperator = rootOperator == null ? LinkType.AND : rootOperator;
    }

    public List<SortCondition> getSorts() {
        return sorts;
    }

    public void setSorts(List<SortCondition> sorts) {
        this.sorts = sorts == null ? new ArrayList<>() : sorts;
    }

    public List<FilterCondition> getFilters() {
        return filters;
    }

    public void setFilters(List<FilterCondition> filters) {
        this.filters = filters == null ? new ArrayList<>() : filters;
    }

    public Nested getNested() {
        return nested;
    }

    public void setNested(Nested nested) {
        this.nested = nested;
    }

    public PageQueryRequest copy() {
        return clone();
    }

    @Override
    public PageQueryRequest clone() {
        PageQueryRequest copy = new PageQueryRequest();
        copy.setPageNum(this.pageNum);
        copy.setPageSize(this.pageSize);
        copy.setNeedTotal(this.needTotal);
        copy.setRootOperator(this.rootOperator);
        if (this.filters != null) {
            List<FilterCondition> copiedFilters = new ArrayList<>(this.filters.size());
            for (FilterCondition f : this.filters) {
                copiedFilters.add(f == null ? null : new FilterCondition(f.getField(), f.getOperator(), f.getValue()));
            }
            copy.setFilters(copiedFilters);
        }
        if (this.sorts != null) {
            List<SortCondition> copiedSorts = new ArrayList<>(this.sorts.size());
            for (SortCondition s : this.sorts) {
                copiedSorts.add(s == null ? null : new SortCondition(s.getField(), s.getDirection()));
            }
            copy.setSorts(copiedSorts);
        }
        if (this.nested != null) {
            Nested copiedNested = new Nested();
            if (this.nested.getAnd() != null) {
                for (FilterCondition f : this.nested.getAnd()) {
                    if (f != null) {
                        copiedNested.addAnd(f.getField(), f.getOperator(), f.getValue());
                    }
                }
            }
            if (this.nested.getOr() != null) {
                for (FilterCondition f : this.nested.getOr()) {
                    if (f != null) {
                        copiedNested.addOr(f.getField(), f.getOperator(), f.getValue());
                    }
                }
            }
            copy.setNested(copiedNested);
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PageQueryRequest that)) {
            return false;
        }
        return pageNum == that.pageNum
                && pageSize == that.pageSize
                && needTotal == that.needTotal
                && rootOperator == that.rootOperator
                && Objects.equals(sorts, that.sorts)
                && Objects.equals(filters, that.filters)
                && Objects.equals(nested, that.nested);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageNum, pageSize, needTotal, rootOperator, sorts, filters, nested);
    }

    /**
     * 嵌套过滤条件组。
     */
    public static final class Nested implements Serializable {
        private static final long serialVersionUID = 1L;

        private List<FilterCondition> and = new ArrayList<>();
        private List<FilterCondition> or = new ArrayList<>();

        public Nested addAnd(String field, FilterOperator operator, Object value) {
            this.and.add(new FilterCondition(field, operator, value));
            return this;
        }

        public Nested addOr(String field, FilterOperator operator, Object value) {
            this.or.add(new FilterCondition(field, operator, value));
            return this;
        }

        public List<FilterCondition> getAnd() {
            return and;
        }

        public void setAnd(List<FilterCondition> and) {
            this.and = and == null ? new ArrayList<>() : and;
        }

        public List<FilterCondition> getOr() {
            return or;
        }

        public void setOr(List<FilterCondition> or) {
            this.or = or == null ? new ArrayList<>() : or;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Nested nested1)) {
                return false;
            }
            return Objects.equals(and, nested1.and) && Objects.equals(or, nested1.or);
        }

        @Override
        public int hashCode() {
            return Objects.hash(and, or);
        }
    }
}
