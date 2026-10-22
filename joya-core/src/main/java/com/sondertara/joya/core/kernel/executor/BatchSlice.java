package com.sondertara.joya.core.kernel.executor;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 批量执行的内核结果值 —— 只承载 JDBC 层事实：逐行影响计数与生成键。
 *
 * <p>存在的理由：{@code kernel} 是最底层，不应认识上层的 {@code MutationResult}
 * （它带有业务语义的 {@code success}、{@code serverInfo} 与合并逻辑）。
 * 由调用方在本层把本类型转换为 {@code MutationResult}。
 *
 * @param batchCounts   逐行影响计数（{@code Statement.SUCCESS_NO_INFO} 保留为 -2）
 * @param generatedKeys 生成键列表，未请求时为空列表
 */
public record BatchSlice(int[] batchCounts, List<Object> generatedKeys) {

    public static final BatchSlice EMPTY = new BatchSlice(new int[0], List.of());

    public BatchSlice {
        batchCounts = batchCounts == null ? new int[0] : batchCounts.clone();
        generatedKeys = generatedKeys == null ? List.of() : List.copyOf(generatedKeys);
    }

    @Override
    public int[] batchCounts() {
        return batchCounts.clone();
    }

    public boolean isEmpty() {
        return batchCounts.length == 0 && generatedKeys.isEmpty();
    }

    public static BatchSlice of(@Nullable int[] batchCounts, @Nullable List<Object> generatedKeys) {
        if ((batchCounts == null || batchCounts.length == 0)
                && (generatedKeys == null || generatedKeys.isEmpty())) {
            return EMPTY;
        }
        return new BatchSlice(batchCounts, generatedKeys);
    }
}
