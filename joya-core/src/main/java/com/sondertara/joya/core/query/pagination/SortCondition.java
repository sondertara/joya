package com.sondertara.joya.core.query.pagination;

import java.io.Serializable;
import java.util.Objects;

/**
 * 排序条件项（字段名 + 排序方向）。
 */
public final class SortCondition implements Serializable {
    private static final long serialVersionUID = 1L;

    private String field;
    private SortDirection direction = SortDirection.ASC;

    public SortCondition() {
    }

    public SortCondition(String field) {
        this(field, SortDirection.ASC);
    }

    public SortCondition(String field, SortDirection direction) {
        this.field = field;
        this.direction = direction == null ? SortDirection.ASC : direction;
    }

    public static SortCondition asc(String field) {
        return new SortCondition(field, SortDirection.ASC);
    }

    public static SortCondition desc(String field) {
        return new SortCondition(field, SortDirection.DESC);
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public SortDirection getDirection() {
        return direction;
    }

    public void setDirection(SortDirection direction) {
        this.direction = direction == null ? SortDirection.ASC : direction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SortCondition that)) {
            return false;
        }
        return Objects.equals(field, that.field) && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, direction);
    }

    @Override
    public String toString() {
        return "SortCondition{field='" + field + "', direction=" + direction + '}';
    }
}
