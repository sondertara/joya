package com.sondertara.joya.core.query.pagination;

import java.io.Serializable;
import java.util.Objects;

/**
 * 动态过滤条件项（字段名 + 操作符 + 值）。
 */
public final class FilterCondition implements Serializable {
    private static final long serialVersionUID = 1L;

    private String field;
    private FilterOperator operator = FilterOperator.EQ;
    private Object value;

    public FilterCondition() {
    }

    public FilterCondition(String field, FilterOperator operator, Object value) {
        this.field = field;
        this.operator = operator == null ? FilterOperator.EQ : operator;
        this.value = value;
    }

    public static FilterCondition of(String field, FilterOperator operator, Object value) {
        return new FilterCondition(field, operator, value);
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public FilterOperator getOperator() {
        return operator;
    }

    public void setOperator(FilterOperator operator) {
        this.operator = operator == null ? FilterOperator.EQ : operator;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FilterCondition that)) {
            return false;
        }
        return Objects.equals(field, that.field)
                && operator == that.operator
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, operator, value);
    }

    @Override
    public String toString() {
        return "FilterCondition{field='" + field + "', operator=" + operator + ", value=" + value + '}';
    }
}
