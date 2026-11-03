package com.sondertara.joya.core.query.pagination;

import java.io.Serializable;
import java.util.Objects;

/**
 * 分页请求值对象（不可变，支持控制是否执行 {@code COUNT(*)} 统计总数）。
 */
public final class PageRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int page;         // 0-based 内部页索引
    private final int size;         // 每页条数
    private final boolean needTotal;

    private PageRequest(int page, int size, boolean needTotal) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        this.page = page;
        this.size = size;
        this.needTotal = needTotal;
    }

    /**
     * 创建分页请求（默认 0-based 页码，且统计总数 {@code needTotal = true}）。
     *
     * @param page 0-based 页码（第 1 页为 {@code 0}）
     * @param size 每页大小（必须 {@code > 0}）
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, true);
    }

    /**
     * 创建分页请求（0-based 页码，显式指定是否统计总数）。
     */
    public static PageRequest of(int page, int size, boolean needTotal) {
        return new PageRequest(page, size, needTotal);
    }

    /**
     * 按 0-based 页码创建分页请求（第 1 页传 {@code 0}）。
     */
    public static PageRequest ofZeroBased(int page, int size) {
        return new PageRequest(page, size, true);
    }

    /**
     * 按 0-based 页码创建分页请求（显式指定是否统计总数）。
     */
    public static PageRequest ofZeroBased(int page, int size, boolean needTotal) {
        return new PageRequest(page, size, needTotal);
    }

    /**
     * 按 1-based 页码创建分页请求（第 1 页传 {@code 1}，内部自动换算为 0-based 页索引）。
     */
    public static PageRequest ofOneBased(int pageNum, int pageSize) {
        return ofOneBased(pageNum, pageSize, true);
    }

    /**
     * 按 1-based 页码创建分页请求（显式指定是否统计总数）。
     */
    public static PageRequest ofOneBased(int pageNum, int pageSize, boolean needTotal) {
        if (pageNum < 1) {
            throw new IllegalArgumentException("1-based pageNum must be >= 1, got: " + pageNum);
        }
        return new PageRequest(pageNum - 1, pageSize, needTotal);
    }

    /**
     * 返回不执行 {@code COUNT(*)} 的分页请求副本（返回的 {@code PageResult.total()} 为 {@code -1L}）。
     */
    public PageRequest withoutTotal() {
        return needTotal ? new PageRequest(page, size, false) : this;
    }

    /** 获取 0-based 页索引（第 1 页为 0）。 */
    public int getPage() {
        return page;
    }

    /** 获取 1-based 页码（第 1 页为 1）。 */
    public int getPageNum() {
        return page < Integer.MAX_VALUE ? page + 1 : Integer.MAX_VALUE;
    }

    /** 获取每页条数。 */
    public int getSize() {
        return size;
    }

    /** 获取每页条数（与 {@link PageQueryRequest#getPageSize()} 命名对齐）。 */
    public int getPageSize() {
        return size;
    }

    /** 是否需要执行 {@code COUNT(*)} 统计总条数。 */
    public boolean isNeedTotal() {
        return needTotal;
    }

    /** 计算 OFFSET（超出 int 范围时抛出 {@link ArithmeticException}）。 */
    public int getOffset() {
        return Math.multiplyExact(page, size);
    }

    /** 计算 64 位 OFFSET。 */
    public long getOffsetAsLong() {
        return (long) page * (long) size;
    }

    /** 计算 LIMIT。 */
    public int getLimit() {
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PageRequest that)) {
            return false;
        }
        return page == that.page && size == that.size && needTotal == that.needTotal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(page, size, needTotal);
    }

    @Override
    public String toString() {
        return "PageRequest{page=" + page + ", pageNum=" + getPageNum()
                + ", size=" + size + ", needTotal=" + needTotal + '}';
    }
}
