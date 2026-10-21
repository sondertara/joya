package com.sondertara.joya.core.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 从实体实例创建快照，按元数据列顺序通过预缓存 {@link java.lang.reflect.Field} 直接读取字段值。 */
    public static EntitySnapshot snapshot(EntityMetadataContext metadata, Object entity) {
        return new EntitySnapshot(metadata, readValues(metadata, entity));
    }

    /** 实体监听器修改后重新快照。 */
    public void refresh(Object entity) {
        this.values = Collections.unmodifiableMap(readValues(metadata, entity));
    }

    private static Map<String, Object> readValues(EntityMetadataContext metadata, Object entity) {
        List<String> columns = metadata.columnNames();
        Map<String, Object> values = new LinkedHashMap<>(columns.size());
        for (String column : columns) {
            if (metadata.fieldByColumn(column) != null) {
                values.put(column, metadata.readFieldValue(entity, column));
            }
        }
        return values;
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
