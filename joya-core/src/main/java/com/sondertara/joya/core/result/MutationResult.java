package com.sondertara.joya.core.result;

import com.sondertara.joya.utils.ConvertUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 变更操作结果 —— 统一承载单条与批量 INSERT/UPDATE/DELETE/UPSERT 的执行结果。
 *
 * @author huangxiaohu
 */
public record MutationResult(
        long affectedRows,                              // 影响行数（自动归一 JDBC SUCCESS_NO_INFO）
        @Nullable List<Object> generatedKeys,           // 生成键（自增 ID、序列、RETURNING）
        @Nullable Map<String, Object> serverInfo        // 数据库特有诊断信息（含 batchCounts 等）
) {

    public static final String KEY_BATCH_COUNTS = "batchCounts";
    private static final int[] EMPTY_COUNTS = new int[0];

    /**
     * 空结果单例。
     */
    public static final MutationResult EMPTY = new MutationResult(0, Collections.emptyList(), Collections.emptyMap());

    /**
     * 构造仅包含影响行数的变更结果（UPDATE / DELETE 常用）。
     */
    public static MutationResult ofAffected(long affectedRows) {
        return new MutationResult(affectedRows, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * 构造单行插入结果（含可选生成键）。
     */
    public static MutationResult ofInsert(long affectedRows, @Nullable Object generatedKey) {
        List<Object> keys = generatedKey == null ? Collections.emptyList() : List.of(generatedKey);
        return new MutationResult(affectedRows, keys, Collections.emptyMap());
    }

    /**
     * 构造批量变更结果（自动处理 JDBC {@link Statement#SUCCESS_NO_INFO} 即 {@code -2} 的影响行数汇总）。
     */
    public static MutationResult ofBatch(int[] batchCounts) {
        return ofBatch(batchCounts, Collections.emptyList());
    }

    /**
     * 构造带生成键列表的批量变更结果。
     */
    public static MutationResult ofBatch(int[] batchCounts, @Nullable List<Object> generatedKeys) {
        int[] safeCounts = batchCounts == null ? EMPTY_COUNTS : batchCounts;
        long totalAffected = sumBatchCounts(safeCounts);
        List<Object> safeKeys = (generatedKeys == null || generatedKeys.isEmpty())
                ? Collections.emptyList()
                : List.copyOf(generatedKeys);
        Map<String, Object> info = safeCounts.length == 0
                ? Collections.emptyMap()
                : Map.of(KEY_BATCH_COUNTS, safeCounts);
        return new MutationResult(totalAffected, safeKeys, info);
    }

    /**
     * 合并两个变更结果（用于多分组批处理或 Upsert 汇总）。
     */
    public MutationResult merge(@Nullable MutationResult other) {
        if (other == null || (other.affectedRows == 0 && !other.hasGeneratedKeys() && other.batchCounts().length == 0)) {
            return this;
        }
        if (this.affectedRows == 0 && !this.hasGeneratedKeys() && this.batchCounts().length == 0) {
            return other;
        }
        List<Object> mergedKeys;
        if (!this.hasGeneratedKeys()) {
            mergedKeys = other.generatedKeys;
        } else if (!other.hasGeneratedKeys()) {
            mergedKeys = this.generatedKeys;
        } else {
            mergedKeys = new ArrayList<>(this.generatedKeys.size() + other.generatedKeys.size());
            mergedKeys.addAll(this.generatedKeys);
            mergedKeys.addAll(other.generatedKeys);
        }
        int[] c1 = this.batchCounts();
        int[] c2 = other.batchCounts();
        int[] mergedCounts;
        if (c1.length == 0) {
            mergedCounts = c2;
        } else if (c2.length == 0) {
            mergedCounts = c1;
        } else {
            mergedCounts = new int[c1.length + c2.length];
            System.arraycopy(c1, 0, mergedCounts, 0, c1.length);
            System.arraycopy(c2, 0, mergedCounts, c1.length, c2.length);
        }
        Map<String, Object> info = mergedCounts.length == 0
                ? Collections.emptyMap()
                : Map.of(KEY_BATCH_COUNTS, mergedCounts);
        return new MutationResult(
                this.affectedRows + other.affectedRows,
                mergedKeys == null ? Collections.emptyList() : Collections.unmodifiableList(mergedKeys),
                info
        );
    }

    /**
     * 获取底层 JDBC 批处理逐行计数数组（无批处理计数时返回空数组）。
     */
    public int[] batchCounts() {
        if (serverInfo != null && serverInfo.get(KEY_BATCH_COUNTS) instanceof int[] counts) {
            return counts;
        }
        return EMPTY_COUNTS;
    }

    public static long sumBatchCounts(int[] counts) {
        if (counts == null || counts.length == 0) {
            return 0L;
        }
        long sum = 0L;
        for (int c : counts) {
            if (c >= 0) {
                sum += c;
            } else if (c == Statement.SUCCESS_NO_INFO) {
                sum += 1L;
            }
        }
        return sum;
    }

    // ========== 生成键便捷访问 ==========

    /**
     * 获取首个生成键（单行插入常用，支持数字类型自动转换）。
     */
    @Nullable
    public <K> K getGeneratedKey(@NonNull Class<K> type) {
        if (generatedKeys == null || generatedKeys.isEmpty() || generatedKeys.get(0) == null) {
            return null;
        }
        Object raw = generatedKeys.get(0);
        if (type.isInstance(raw)) {
            return type.cast(raw);
        }
        return ConvertUtils.convert(raw, type);
    }

    /**
     * 获取所有生成键（批量插入 RETURNING 多行）。
     */
    @NonNull
    public <K> List<K> getGeneratedKeys(@NonNull Class<K> type) {
        if (generatedKeys == null || generatedKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return generatedKeys.stream()
                .map(raw -> type.isInstance(raw) ? type.cast(raw) : ConvertUtils.convert(raw, type))
                .toList();
    }

    // ========== 状态判断 ==========

    public boolean isEmpty() {
        return affectedRows == 0;
    }

    public boolean hasGeneratedKeys() {
        return generatedKeys != null && !generatedKeys.isEmpty();
    }
}
