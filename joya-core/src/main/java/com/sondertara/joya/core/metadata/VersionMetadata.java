package com.sondertara.joya.core.metadata;

import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 实体乐观锁版本列元数据（对应标准 JPA {@code @Version}）：
 * 统一收口版本列类型校验、初始值生成、防溢出递增及非空前置校验。
 */
public final class VersionMetadata {

    private final String columnName;
    private final int columnIndex;
    private final Class<?> javaType;

    VersionMetadata(String columnName, int columnIndex, Class<?> javaType) {
        this.columnName = Objects.requireNonNull(columnName, "version columnName must not be null");
        if (columnIndex < 0) {
            throw new IllegalArgumentException("version columnIndex must be >= 0: " + columnIndex);
        }
        this.columnIndex = columnIndex;
        this.javaType = Objects.requireNonNull(javaType, "version javaType must not be null");
    }

    public static boolean isSupportedType(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class;
    }

    public static void validateType(Class<?> entityType, String fieldName, Class<?> fieldType) {
        if (!isSupportedType(fieldType)) {
            throw new DbException("Unsupported @Version type [" + fieldType.getName() + "] on field ["
                    + fieldName + "] of entity [" + entityType.getName()
                    + "]; supported types are short/Short, int/Integer, long/Long");
        }
    }

    public String columnName() {
        return columnName;
    }

    public int columnIndex() {
        return columnIndex;
    }

    public Class<?> javaType() {
        return javaType;
    }

    /** 返回该版本列类型对应的初始版本号（0 / 0L / (short) 0）。 */
    public Object initialValue() {
        if (javaType == long.class || javaType == Long.class) {
            return 0L;
        }
        if (javaType == short.class || javaType == Short.class) {
            return (short) 0;
        }
        return 0;
    }

    /** 校验更新/删除前实体携带的当前版本号非空。 */
    public Object requireCurrent(@Nullable Object current, Class<?> entityType, @Nullable Object primaryKey) {
        if (current == null) {
            throw new IllegalArgumentException("Entity @Version column '" + columnName
                    + "' must not be null for optimistic-locked operation on "
                    + entityType.getName() + " (id=" + primaryKey + ")");
        }
        return current;
    }

    /** 计算严格递增后的下一版本号（带整型溢出保护，返回精确同类型数值）。 */
    public Object nextValue(Object current) {
        Objects.requireNonNull(current, "current version must not be null");
        if (javaType == long.class || javaType == Long.class) {
            return Math.addExact(((Number) current).longValue(), 1L);
        }
        if (javaType == short.class || javaType == Short.class) {
            int next = ((Number) current).intValue() + 1;
            if (next > Short.MAX_VALUE || next < Short.MIN_VALUE) {
                throw new ArithmeticException("Short @Version overflow on column '" + columnName + "'");
            }
            return (short) next;
        }
        return Math.addExact(((Number) current).intValue(), 1);
    }
}
