package com.sondertara.joya.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 集合分片工具 —— 批量写、批量删、批量查共用同一份实现，避免分片逻辑被复制到多个职责类。
 */
final class Partitions {

    /** IN 子句单批最大元素数（Oracle 等数据库的硬限制）。 */
    static final int MAX_IN_CLAUSE_SIZE = 1000;

    private Partitions() {
    }

    static <E> List<List<E>> of(List<E> source, int size) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        int chunkSize = size <= 0 ? source.size() : size;
        int chunks = (source.size() + chunkSize - 1) / chunkSize;
        List<List<E>> result = new ArrayList<>(chunks);
        for (int i = 0; i < source.size(); i += chunkSize) {
            result.add(source.subList(i, Math.min(source.size(), i + chunkSize)));
        }
        return result;
    }
}
