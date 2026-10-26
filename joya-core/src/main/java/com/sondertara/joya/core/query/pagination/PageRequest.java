package com.sondertara.joya.core.query.pagination;

import java.io.Serializable;
import java.util.Objects;

/**
 * 分页请求对象（不可变，0-indexed）。
 * 替代 {@link PageParam}，提供更清晰的 API。
 */
public final class PageRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int page;   // 0-based
    private final int size;

    private PageRequest(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (size <= 0) throw new IllegalArgumentException("size must be > 0");
        this.page = page;
        this.size = size;
    }

    /**
     * 创建分页请求。
     * @param page 页码（0-indexed）
     * @param size 每页大小
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    public int getPage() { return page; }
    public int getSize() { return size; }

    /** 计算 OFFSET（超出 int 范围时抛出 {@link ArithmeticException}） */
    public int getOffset() { return Math.multiplyExact(page, size); }

    /** 计算 64 位 OFFSET */
    public long getOffsetAsLong() { return (long) page * (long) size; }

    /** 计算 LIMIT */
    public int getLimit() { return size; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageRequest)) return false;
        PageRequest that = (PageRequest) o;
        return page == that.page && size == that.size;
    }

    @Override
    public int hashCode() {
        return Objects.hash(page, size);
    }

    @Override
    public String toString() {
        return "PageRequest{page=" + page + ", size=" + size + '}';
    }
}
