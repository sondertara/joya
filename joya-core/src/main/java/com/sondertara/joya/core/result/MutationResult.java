package com.sondertara.joya.core.result;

import com.sondertara.common.convert.ConvertUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 变更操作结果 —— 统一承载 INSERT/UPDATE/DELETE/UPSERT 的执行结果。
 *
 * @author huangxiaohu
 */
public record MutationResult(
        long affectedRows,                              // 影响行数
        @Nullable List<Object> generatedKeys,           // 生成键（自增 ID、序列、RETURNING 单列）
        @Nullable Map<String, Object> serverInfo,       // 数据库特有诊断信息（PG oid、MySQL info 等）
        boolean success                                 // 无异常即 true
) {

    /**
     * 空结果单例。
     */
    public static final MutationResult EMPTY = new MutationResult(0, Collections.emptyList(), Collections.emptyMap(), true);

    /**
     * 构造仅包含影响行数的变更结果（UPDATE / DELETE 常用）。
     */
    public static MutationResult ofAffected(long affectedRows) {
        return new MutationResult(affectedRows, Collections.emptyList(), Collections.emptyMap(), true);
    }

    /**
     * 构造单行插入结果（含可选生成键）。
     */
    public static MutationResult ofInsert(long affectedRows, @Nullable Object generatedKey) {
        List<Object> keys = generatedKey == null ? Collections.emptyList() : List.of(generatedKey);
        return new MutationResult(affectedRows, keys, Collections.emptyMap(), true);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MutationResult that)) return false;
        return affectedRows == that.affectedRows &&
                success == that.success &&
                Objects.equals(generatedKeys, that.generatedKeys) &&
                Objects.equals(serverInfo, that.serverInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(affectedRows, generatedKeys, serverInfo, success);
    }

    @Override
    public String toString() {
        return "MutationResult{" +
                "affectedRows=" + affectedRows +
                ", generatedKeys=" + generatedKeys +
                ", serverInfo=" + serverInfo +
                ", success=" + success +
                '}';
    }
}