package com.sondertara.joya.core.kernel;

import java.util.List;
import java.util.Objects;

/**
 * 内核分页数据切片（仅承载当前页数据行与总条数，与上层查询分页请求/响应模型解耦）。
 *
 * @param rows  当前页数据列表
 * @param total 总记录数
 * @param <T>   行元素类型
 */
public record PageSlice<T>(
        List<T> rows,
        long total
) {
    public PageSlice {
        rows = rows == null ? List.of() : List.copyOf(Objects.requireNonNull(rows));
    }
}
