package com.sondertara.joya.core.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体快照 —— 从实体实例一次性读取出的不可变字段值快照，单次操作生命周期。
 * <p>
 * 与 {@link EntityMetadataContext} 分离：元数据是缓存级只读结构，快照在 {@code prePersist}/{@code preUpdate} 生命周期回调完成后创建。
 */
public final class EntitySnapshot {

    private final Map<String, Object> values;

    private EntitySnapshot(Map<String, Object> values) {
        // 容忍 null 字段值（如自增主键、可选列）：Map.copyOf 会拒绝 null value 抛 NPE。
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 从实体实例创建快照，按元数据列顺序通过预缓存 {@link java.lang.reflect.Field} 直接读取字段值。 */
    public static EntitySnapshot snapshot(EntityMetadataContext metadata, Object entity) {
        return new EntitySnapshot(metadata.readFieldValues(entity));
    }

    /** 获取快照值（不可变视图）。 */
    public Map<String, Object> values() {
        return values;
    }

    /** 获取指定列的值。 */
    public Object value(String columnName) {
        return values.get(columnName);
    }
}
