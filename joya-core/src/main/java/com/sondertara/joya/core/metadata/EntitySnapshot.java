package com.sondertara.joya.core.metadata;

import com.sondertara.common.reflect.ReflectUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实体快照 —— 从实体实例读取出的字段值快照，单次操作生命周期。
 * <p>
 * 与 {@link EntityMetadataContext} 分离：元数据是缓存级只读的，快照是单次操作的可变数据。
 * 实体监听器修改实体后必须显式调用 {@link #refresh(Object)} 重新快照，而非依赖隐式 resolved 守卫。
 */
public final class EntitySnapshot {

    private final EntityMetadataContext metadata;
    private Map<String, Object> values;

    private EntitySnapshot(EntityMetadataContext metadata, Map<String, Object> values) {
        this.metadata = metadata;
        // 容忍 null 字段值（如自增主键、可选列）：Map.copyOf 会拒绝 null value 抛 NPE。
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 从实体实例创建快照，按元数据列顺序读取字段值。 */
    public static EntitySnapshot snapshot(EntityMetadataContext metadata, Object entity) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : metadata.columnNames()) {
            String fieldName = metadata.fieldByColumn(column);
            if (fieldName != null) {
                values.put(column, ReflectUtils.getFieldValue(entity, fieldName));
            }
        }
        return new EntitySnapshot(metadata, values);
    }

    /** 实体监听器修改后重新快照。 */
    public void refresh(Object entity) {
        Map<String, Object> newValues = new LinkedHashMap<>();
        for (String column : metadata.columnNames()) {
            String fieldName = metadata.fieldByColumn(column);
            if (fieldName != null) {
                newValues.put(column, ReflectUtils.getFieldValue(entity, fieldName));
            }
        }
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(newValues));
    }

    /** 获取快照值（不可变视图）。 */
    public Map<String, Object> values() {
        return values;
    }

    /** 获取指定列的值。 */
    public Object value(String columnName) {
        return values.get(columnName);
    }

    /** 元数据上下文。 */
    public EntityMetadataContext metadata() {
        return metadata;
    }
}
