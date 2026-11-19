package com.sondertara.joya.core.kernel.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 有序扩展点标记接口。
 * <p>
 * 排序规则：{@code order()} 升序，同值按 {@code Class.getName()} 字典序保证确定性。
 */
public interface Ordered {
    int HIGHEST = Integer.MIN_VALUE;
    int LOWEST = Integer.MAX_VALUE;

    int order();

    /**
     * 按 {@link #order()} 升序（同值按实现类全限定名字典序）返回不可变排序列表。
     */
    static <T extends Ordered> List<T> sort(List<T> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(Ordered::order)
                .thenComparing(item -> item.getClass().getName()));
        return List.copyOf(sorted);
    }
}
