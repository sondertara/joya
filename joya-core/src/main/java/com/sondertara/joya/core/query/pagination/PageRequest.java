package com.sondertara.joya.core.query.pagination;

import java.io.Serializable;
import java.util.Objects;

/**
 * 分页请求值对象（不可变，支持控制是否执行 {@code COUNT(*)} 统计总数）。
 */
public final class PageRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 单页最大条数，防止一次拉取过多数据。 */
    public static final int MAX_PAGE_SIZE = 2000;

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
        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be <= " + MAX_PAGE_SIZE + ": " + size);
        }
        this.page = page;
        this.size = size;
        this.needTotal = needTotal;
    }

    /**
     * 创建分页请求（0-based 页索引，默认统计总数 {@code needTotal = true}）。
     *
     * @param page 0-based 页索引（第 1 页为 {@code 0}）
     * @param size 每页条数（必须 {@code > 0} 且 {@code <= MAX_PAGE_SIZE}）
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, true);
    }

    /**
     * 创建分页请求（0-based 页索引，显式指定是否统计总数）。
     */
    public static PageRequest of(int page, int size, boolean needTotal) {
        return new PageRequest(page, size, needTotal);
    }

    /**
     * 返回不执行 {@code COUNT(*)} 的分页请求副本（返回的 {@code PageResult.total()} 为 {@code -1L}）。
     */
    public PageRequest withoutTotal() {
        return needTotal ? new PageRequest(page, size, false) : this;
    }

    /** 0-based 页索引（第 1 页为 0）。 */
    public int page() {
        return page;
    }

    /** 0-based 页索引（Getter 形式）。 */
    public int getPage() {
        return page;
    }

    /** 每页条数。 */
    public int size() {
        return size;
    }

    /** 每页条数（Getter 形式）。 */
    public int getSize() {
        return size;
    }

    /** 是否需要执行 {@code COUNT(*)} 统计总条数。 */
    public boolean needTotal() {
        return needTotal;
    }

    /** 是否需要执行 {@code COUNT(*)} 统计总条数（Getter 形式）。 */
    public boolean isNeedTotal() {
        return needTotal;
    }

    /** 计算 64 位 OFFSET（防 int 溢出）。 */
    public long offset() {
        return (long) page * (long) size;
    }

    /** 计算 64 位 OFFSET（Getter 形式）。 */
    public long getOffset() {
        return offset();
    }

    /** 计算 LIMIT。 */
    public int limit() {
        return size;
    }

    /** 计算 LIMIT（Getter 形式）。 */
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
        return "PageRequest{page=" + page + ", size=" + size + ", needTotal=" + needTotal + '}';
    }
}
