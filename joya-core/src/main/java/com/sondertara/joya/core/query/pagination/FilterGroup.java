package com.sondertara.joya.core.query.pagination;

import com.sondertara.joya.core.query.LogicalOperator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 动态查询中的逻辑过滤条件组（支持 {@code AND} / {@code OR} 组合与递归嵌套）。
 *
 * <pre>{@code
 * // 表达: (name = 'Alice' OR name = 'Bob')
 * FilterGroup group = FilterGroup.or()
 *     .addFilter("name", FilterOperator.EQ, "Alice")
 *     .addFilter("name", FilterOperator.EQ, "Bob");
 * }</pre>
 */
public final class FilterGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 默认最大递归嵌套层级，防止畸形输入导致深层递归或栈溢出。 */
    public static final int MAX_DEPTH = 8;

    /** 组内条件及子条件组之间的逻辑运算符，默认 AND。 */
    private LogicalOperator operator = LogicalOperator.AND;

    /** 组内过滤条件列表。 */
    private List<FilterCondition> filters = new ArrayList<>();

    /** 递归子过滤条件组列表。 */
    private List<FilterGroup> filterGroups = new ArrayList<>();

    public FilterGroup() {
    }

    public FilterGroup(LogicalOperator operator) {
        this.operator = operator == null ? LogicalOperator.AND : operator;
    }

    public static FilterGroup and() {
        return new FilterGroup(LogicalOperator.AND);
    }

    public static FilterGroup or() {
        return new FilterGroup(LogicalOperator.OR);
    }

    public FilterGroup operator(LogicalOperator operator) {
        this.operator = operator == null ? LogicalOperator.AND : operator;
        return this;
    }

    public FilterGroup filters(List<FilterCondition> filters) {
        this.filters = filters == null ? new ArrayList<>() : new ArrayList<>(filters);
        return this;
    }

    public FilterGroup filterGroups(List<FilterGroup> filterGroups) {
        this.filterGroups = filterGroups == null ? new ArrayList<>() : new ArrayList<>(filterGroups);
        return this;
    }

    public FilterGroup addFilter(FilterCondition condition) {
        if (condition != null) {
            if (this.filters == null) {
                this.filters = new ArrayList<>();
            }
            this.filters.add(condition);
        }
        return this;
    }

    public FilterGroup addFilter(String field, FilterOperator operator, Object value) {
        return addFilter(new FilterCondition(field, operator, value));
    }

    public FilterGroup addFilterGroup(FilterGroup group) {
        if (group != null) {
            if (this.filterGroups == null) {
                this.filterGroups = new ArrayList<>();
            }
            this.filterGroups.add(group);
        }
        return this;
    }

    public FilterGroup addAndGroup(Consumer<FilterGroup> customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        FilterGroup group = FilterGroup.and();
        customizer.accept(group);
        return addFilterGroup(group);
    }

    public FilterGroup addOrGroup(Consumer<FilterGroup> customizer) {
        Objects.requireNonNull(customizer, "customizer must not be null");
        FilterGroup group = FilterGroup.or();
        customizer.accept(group);
        return addFilterGroup(group);
    }

    public LogicalOperator getOperator() {
        return operator;
    }

    public void setOperator(LogicalOperator operator) {
        this.operator = operator == null ? LogicalOperator.AND : operator;
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

    public FilterGroup copy() {
        return copy(0);
    }

    private FilterGroup copy(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "FilterGroup nesting depth exceeds maximum allowed (" + MAX_DEPTH + ")");
        }
        FilterGroup copy = new FilterGroup(this.operator);
        if (this.filters != null) {
            List<FilterCondition> copiedFilters = new ArrayList<>(this.filters.size());
            for (FilterCondition f : this.filters) {
                copiedFilters.add(f == null ? null : new FilterCondition(f.getField(), f.getOperator(), f.getValue()));
            }
            copy.setFilters(copiedFilters);
        }
        if (this.filterGroups != null) {
            List<FilterGroup> copiedGroups = new ArrayList<>(this.filterGroups.size());
            for (FilterGroup g : this.filterGroups) {
                copiedGroups.add(g == null ? null : g.copy(depth + 1));
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
        if (!(o instanceof FilterGroup that)) {
            return false;
        }
        return operator == that.operator
                && Objects.equals(filters, that.filters)
                && Objects.equals(filterGroups, that.filterGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, filters, filterGroups);
    }

    @Override
    public String toString() {
        return "FilterGroup{operator=" + operator + ", filters=" + filters
                + ", filterGroups=" + filterGroups + '}';
    }
}
