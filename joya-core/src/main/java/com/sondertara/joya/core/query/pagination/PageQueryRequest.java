package com.sondertara.joya.core.query.pagination;

import com.sondertara.joya.core.query.LogicalOperator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 通用动态分页查询请求协议（0-based 页索引）：
 * <pre>{@code
 * {
 *   "page": 0,
 *   "size": 20,
 *   "needTotal": true,
 *   "sorts": [ { "field": "createTime", "direction": "DESC" } ],
 *   "filters": [ { "field": "status", "operator": "EQ", "value": 1 } ],
 *   "filterGroups": [
 *     { "operator": "OR", "filters": [ { "field": "name", "operator": "EQ", "value": "Alice" } ] }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code filters} 与 {@code sorts} 的字段在通过 {@code PageQueryParams.of(request)} 应用到 {@code Query} 时，
 * 强制由 {@code RenderContext} 校验是否属于当前查询关联实体的合法列白名单，非法或未知字段一律拒绝。
 */
public class PageQueryRequest implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;

    /** 页索引，0-based，默认 0 */
    private int page = 0;

    /** 每页条数，默认 20 */
    private int size = 20;

    /** 是否统计总数，默认 true */
    private boolean needTotal = true;

    /** 顶层过滤条件之间的连接方式，默认 AND */
    private LogicalOperator rootOperator = LogicalOperator.AND;

    /** 排序条件列表 */
    private List<SortCondition> sorts = new ArrayList<>();

    /** 过滤条件列表 */
    private List<FilterCondition> filters = new ArrayList<>();

    /** 逻辑过滤条件组列表（支持 AND / OR 组合与递归嵌套） */
    private List<FilterGroup> filterGroups = new ArrayList<>();

    public PageQueryRequest() {
    }

    public PageQueryRequest(int page, int size) {
        this.page = page;
        this.size = size;
    }

    /** 页索引归一化：小于 0 按 0 处理。 */
    public int normalizedPage() {
        return Math.max(page, 0);
    }

    /** 每页条数归一化：小于 1 按 1 处理，大于 {@link PageRequest#MAX_PAGE_SIZE} 截断。 */
    public int normalizedSize() {
        return Math.min(Math.max(size, 1), PageRequest.MAX_PAGE_SIZE);
    }

    /** 当前页之前需要跳过的记录数（64 位防溢出）。 */
    public long offset() {
        return (long) normalizedPage() * (long) normalizedSize();
    }

    /**
     * 提取分页窗口坐标 {@link PageRequest}。
     */
    public PageRequest toPageRequest() {
        return PageRequest.of(normalizedPage(), normalizedSize(), needTotal);
    }

    // ===== 流式构建与便捷操作 =====

    public PageQueryRequest page(int page) {
        this.page = page;
        return this;
    }

    public PageQueryRequest size(int size) {
        this.size = size;
        return this;
    }

    public PageQueryRequest needTotal(boolean needTotal) {
        this.needTotal = needTotal;
        return this;
    }

    public PageQueryRequest rootOperator(LogicalOperator rootOperator) {
        this.rootOperator = rootOperator == null ? LogicalOperator.AND : rootOperator;
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

    public PageQueryRequest filterGroups(List<FilterGroup> filterGroups) {
        this.filterGroups = filterGroups == null ? new ArrayList<>() : new ArrayList<>(filterGroups);
        return this;
    }

    public PageQueryRequest addFilterGroup(FilterGroup group) {
        if (group != null) {
            if (this.filterGroups == null) {
                this.filterGroups = new ArrayList<>();
            }
            this.filterGroups.add(group);
        }
        return this;
    }

    public PageQueryRequest addAndGroup(Consumer<FilterGroup> customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        FilterGroup group = FilterGroup.and();
        customizer.accept(group);
        return addFilterGroup(group);
    }

    public PageQueryRequest addOrGroup(Consumer<FilterGroup> customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        FilterGroup group = FilterGroup.or();
        customizer.accept(group);
        return addFilterGroup(group);
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

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public boolean isNeedTotal() {
        return needTotal;
    }

    public void setNeedTotal(boolean needTotal) {
        this.needTotal = needTotal;
    }

    public LogicalOperator getRootOperator() {
        return rootOperator;
    }

    public void setRootOperator(LogicalOperator rootOperator) {
        this.rootOperator = rootOperator == null ? LogicalOperator.AND : rootOperator;
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

    public List<FilterGroup> getFilterGroups() {
        return filterGroups;
    }

    public void setFilterGroups(List<FilterGroup> filterGroups) {
        this.filterGroups = filterGroups == null ? new ArrayList<>() : filterGroups;
    }

    public PageQueryRequest copy() {
        return clone();
    }

    @Override
    public PageQueryRequest clone() {
        PageQueryRequest copy = new PageQueryRequest();
        copy.setPage(this.page);
        copy.setSize(this.size);
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
        if (this.filterGroups != null) {
            List<FilterGroup> copiedGroups = new ArrayList<>(this.filterGroups.size());
            for (FilterGroup g : this.filterGroups) {
                copiedGroups.add(g == null ? null : g.copy());
            }
            copy.setFilterGroups(copiedGroups);
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
        return page == that.page
                && size == that.size
                && needTotal == that.needTotal
                && rootOperator == that.rootOperator
                && Objects.equals(sorts, that.sorts)
                && Objects.equals(filters, that.filters)
                && Objects.equals(filterGroups, that.filterGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(page, size, needTotal, rootOperator, sorts, filters, filterGroups);
    }

    @Override
    public String toString() {
        return "PageQueryRequest{page=" + page + ", size=" + size
                + ", needTotal=" + needTotal + ", rootOperator=" + rootOperator
                + ", sorts=" + sorts + ", filters=" + filters
                + ", filterGroups=" + filterGroups + '}';
    }
}
