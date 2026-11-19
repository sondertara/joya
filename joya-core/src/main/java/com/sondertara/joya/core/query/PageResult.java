package com.sondertara.joya.core.query;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 不可变分页响应结果（0-based 页索引；同时支持统计总数模式与 {@code needTotal = false} 免 COUNT 切片模式）。
 *
 * @param <T> 行数据类型
 */
public final class PageResult<T> {

    private final List<T> items;
    private final long total;
    private final int page;         // 0-based 页索引（>= 0）
    private final int size;
    /** 免 COUNT 模式下的显式 hasNext；null 表示按 total/items 推导。 */
    private final Boolean hasNextOverride;

    public PageResult(List<T> items, Long total, int page, int size) {
        this(items, total, page, size, null);
    }

    public PageResult(List<T> items, Long total, int page, int size,
                      @org.jspecify.annotations.Nullable Boolean hasNextOverride) {
        this.items = items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
        this.total = (total == null || total < 0L) ? -1L : total;
        this.page = Math.max(0, page);
        this.size = size < 1 ? 20 : size;
        this.hasNextOverride = hasNextOverride;
    }

    /**
     * 直接构造分页结果（{@code page} 为 0-based 页索引；当未统计总数时 {@code total} 传 {@code -1L}）。
     */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /**
     * 直接构造分页结果，并显式指定 {@code hasNext}（免 COUNT 模式使用）。
     */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size, boolean hasNext) {
        return new PageResult<>(items, total, page, size, hasNext);
    }

    /**
     * 未命中任何数据的空页（已知总记录数为 {@code 0L}）。
     */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    /**
     * 开启流式 Builder 构建分页结果。
     */
    public static <T> Builder<T> of(List<T> data) {
        return new Builder<>(data);
    }

    /** 将当前页数据逐条映射为目标类型，完整保留总数、页索引、每页条数与免 COUNT 模式的 {@code hasNext} 状态。 */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (items.isEmpty()) {
            return new PageResult<>(List.of(), total, page, size, hasNextOverride);
        }
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(mapper.apply(item));
        }
        return new PageResult<>(mapped, total, page, size, hasNextOverride);
    }

    /** 当前页数据列表。 */
    public List<T> items() {
        return items;
    }

    /** 当前页数据列表（Getter 形式）。 */
    public List<T> getItems() {
        return items;
    }

    /** 总记录数（{@code needTotal = false} 时为 {@code -1L}）。 */
    public long total() {
        return total;
    }

    /** 总记录数（Getter 形式）。 */
    public long getTotal() {
        return total;
    }

    /** 当前分页结果是否包含已统计的总记录数（{@code total >= 0}）。 */
    public boolean hasTotal() {
        return total >= 0L;
    }

    /** 0-based 当前页索引（第 1 页为 0）。 */
    public int page() {
        return page;
    }

    /** 0-based 当前页索引（Getter 形式）。 */
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

    /**
     * 总页数：
     * <ul>
     *   <li>{@code total < 0}（未统计总数）时返回 {@code -1}；</li>
     *   <li>{@code total == 0} 时返回 {@code 0}；</li>
     *   <li>其余返回向上取整页数。</li>
     * </ul>
     */
    public int totalPages() {
        if (this.total < 0L || this.size <= 0) {
            return -1;
        }
        if (this.total == 0L) {
            return 0;
        }
        long pages = (this.total - 1L) / this.size + 1L;
        return pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages;
    }

    /** 总页数（Getter 形式）。 */
    public int getTotalPages() {
        return totalPages();
    }

    /** 是否有上一页。 */
    public boolean hasPrev() {
        return page > 0;
    }

    public boolean getHasPrev() {
        return hasPrev();
    }

    /** 是否有下一页（未统计总数时必须由调用方显式给出 {@code hasNext}，否则无法可靠推导）。 */
    public boolean hasNext() {
        if (hasNextOverride != null) {
            return hasNextOverride;
        }
        if (total < 0L) {
            // 未统计总数：当前页已按 size 裁剪，无法判断是否还有下一页。
            // 需要该语义的调用方（SqlOps.page / QueryExecutor）必须传入显式 hasNext。
            return false;
        }
        return ((long) page + 1L) * (long) size < total;
    }

    public boolean getHasNext() {
        return hasNext();
    }

    @JsonIgnore
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
                && page == that.page
                && size == that.size
                && Objects.equals(hasNextOverride, that.hasNextOverride)
                && Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, total, page, size, hasNextOverride);
    }

    @Override
    public String toString() {
        return "PageResult{page=" + page + ", size=" + size
                + ", total=" + total + ", items=" + items.size() + '}';
    }

    public static final class Builder<T> {

        private final List<T> data;
        private int page = 0;
        private int size = 20;
        private Long total = -1L;
        private Boolean hasNext;

        public Builder(List<T> data) {
            this.data = data;
        }

        /**
         * 设置 0-based 页索引与每页条数。
         */
        public Builder<T> page(int page, int size) {
            this.page = Math.max(0, page);
            this.size = size;
            return this;
        }

        public Builder<T> total(Long total) {
            this.total = total;
            return this;
        }

        /** 免 COUNT 模式下显式指定是否还有下一页。 */
        public Builder<T> hasNext(boolean hasNext) {
            this.hasNext = hasNext;
            return this;
        }

        public PageResult<T> build() {
            return new PageResult<>(data, total, page, size, hasNext);
        }
    }
}
