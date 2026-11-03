package com.sondertara.joya.core.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 不可变分页响应结果（同时支持统计总数模式与 {@code needTotal = false} 免 COUNT 切片模式）。
 *
 * @param <T> 行数据类型
 */
public final class PageResult<T> {

    private final List<T> items;
    private final long total;
    private final int pageNum;      // 1-based 页码（>= 1）
    private final int pageSize;

    public PageResult(List<T> items, Long total, int currentPage, int pageSize) {
        this.items = items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
        this.total = total == null ? 0L : total;
        this.pageNum = currentPage < 1 ? 1 : currentPage;
        this.pageSize = pageSize < 1 ? 20 : pageSize;
    }

    /**
     * 直接构造分页结果（{@code pageNum} 为 1-based 页码；当未统计总数时 {@code total} 传 {@code -1L}）。
     */
    public static <T> PageResult<T> of(List<T> items, long total, int pageNum, int pageSize) {
        return new PageResult<>(items, total, pageNum, pageSize);
    }

    /**
     * 未命中任何数据的空页。
     */
    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(List.of(), 0L, pageNum, pageSize);
    }

    /**
     * 开启流式 Builder 构建分页结果。
     */
    public static <T> Builder<T> of(List<T> data) {
        return new Builder<>(data);
    }

    /** 当前页数据列表（与 {@code kube-data-query} 协议一致）。 */
    public List<T> items() {
        return items;
    }

    /** 当前页数据列表（Getter 形式）。 */
    public List<T> getItems() {
        return items;
    }

    /** 当前页数据列表。 */
    public List<T> getData() {
        return items;
    }

    /** 总记录数（{@code needTotal = false} 时为 {@code -1L}）。 */
    public long total() {
        return total;
    }

    /** 总记录数。 */
    public Long getTotal() {
        return total;
    }

    /** 1-based 当前页码（第 1 页为 1）。 */
    public int pageNum() {
        return pageNum;
    }

    /** 1-based 当前页码（Getter 形式）。 */
    public int getPageNum() {
        return pageNum;
    }

    /** 0-based 当前页索引（第 1 页为 0）。 */
    public int getCurrentPage() {
        return pageNum - 1;
    }

    /** 每页大小。 */
    public int pageSize() {
        return pageSize;
    }

    /** 每页大小（Getter 形式）。 */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 总页数：
     * <ul>
     *   <li>{@code total < 0}（未统计总数）时返回 {@code -1}；</li>
     *   <li>{@code total == 0} 时返回 {@code 0}；</li>
     *   <li>其余返回向上取整页数。</li>
     * </ul>
     */
    public int getTotalPages() {
        if (this.total < 0L || this.pageSize <= 0) {
            return -1;
        }
        if (this.total == 0L) {
            return 0;
        }
        return (int) ((this.total + this.pageSize - 1L) / this.pageSize);
    }

    /** 是否有上一页。 */
    public boolean hasPrev() {
        return pageNum > 1;
    }

    public boolean getHasPrev() {
        return hasPrev();
    }

    /** 是否有下一页（未统计总数时按“当前页取满 {@code pageSize}”判断）。 */
    public boolean hasNext() {
        if (total < 0L) {
            return items.size() >= pageSize;
        }
        return pageNum < getTotalPages();
    }

    public boolean getHasNext() {
        return hasNext();
    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PageResult<?> that)) {
            return false;
        }
        return total == that.total
                && pageNum == that.pageNum
                && pageSize == that.pageSize
                && Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, total, pageNum, pageSize);
    }

    @Override
    public String toString() {
        return "PageResult{pageNum=" + pageNum + ", pageSize=" + pageSize
                + ", total=" + total + ", items=" + items.size() + '}';
    }

    public static final class Builder<T> {

        private final List<T> data;
        private int pageNum = 1;
        private int pageSize = 20;
        private Long total = 0L;

        public Builder(List<T> data) {
            this.data = data;
        }

        /**
         * 设置 0-based 页索引与每页大小（内部自动换算为 1-based {@code pageNum}）。
         */
        public Builder<T> pagination(int zeroBasedPage, int pageSize) {
            this.pageNum = zeroBasedPage < Integer.MAX_VALUE ? Math.max(1, zeroBasedPage + 1) : Integer.MAX_VALUE;
            this.pageSize = pageSize;
            return this;
        }

        public Builder<T> total(Long total) {
            this.total = total;
            return this;
        }

        public PageResult<T> build() {
            return new PageResult<>(data, total, pageNum, pageSize);
        }
    }
}
