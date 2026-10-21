package com.sondertara.joya.core.metadata;

import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 操作上下文 —— 本次 INSERT / UPDATE / DELETE 的 values、条件和选项，单次 Builder 生命周期。
 * <p>
 * 与 {@link EntityMetadataContext} 严格分离：values 不写入元数据，元数据不被操作污染。
 *
 * @param metadata        实体元数据（只读、可缓存复用）
 * @param values          本次操作的列值（字段名或列名 → 值）
 * @param where           条件（UPDATE/DELETE 使用）
 * @param declaredColumns 显式指定列集合（仅影响本次操作）
 * @param ignoreNull      true 时过滤 null 值
 * @param allowAll        无 WHERE 条件时是否允许全表操作（默认 false，安全拒绝）
 * @param settings        实例级客户端配置（控制表名/列名大小写等）
 */
public record MutationContext(
        EntityMetadataContext metadata,
        Map<String, Object> values,
        WhereCriterion where,
        Set<String> declaredColumns,
        boolean ignoreNull,
        boolean allowAll,
        JoyaSettings settings
) {

    public MutationContext(
            EntityMetadataContext metadata,
            Map<String, Object> values,
            WhereCriterion where,
            Set<String> declaredColumns,
            boolean ignoreNull,
            boolean allowAll
    ) {
        this(metadata, values, where, declaredColumns, ignoreNull, allowAll, JoyaSettings.DEFAULT);
    }

    public MutationContext {
        // 容忍 null 字段值（如自增主键）：Map.copyOf 拒绝 null value 抛 NPE。
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        declaredColumns = declaredColumns == null ? Set.of() : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(declaredColumns));
        settings = Objects.requireNonNullElse(settings, JoyaSettings.DEFAULT);
    }

    /** 便捷构造：仅元数据 + values。 */
    public static MutationContext of(EntityMetadataContext metadata, Map<String, Object> values) {
        return new MutationContext(metadata, values, null, Set.of(), false, false, JoyaSettings.DEFAULT);
    }

    /** 便捷构造：元数据 + values + ignoreNull。 */
    public static MutationContext of(EntityMetadataContext metadata, Map<String, Object> values, boolean ignoreNull) {
        return new MutationContext(metadata, values, null, Set.of(), ignoreNull, false, JoyaSettings.DEFAULT);
    }
}
