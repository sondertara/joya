package com.sondertara.joya.core.metadata;

import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.Objects;

/**
 * 双向列值转换器句柄 —— 封装标准 JPA {@code AttributeConverter<X, Y>}（兼容 {@code jakarta} 与 {@code javax} 双栈），
 * 经预绑定 {@link MethodHandle} 以 {@code invokeExact} 零反射开销执行读写双向转换。
 */
public final class ColumnConverter {

    private final Class<?> converterType;
    private final Class<?> entityAttributeType;
    private final Class<?> databaseColumnType;
    private final MethodHandle toDatabaseHandle;
    private final MethodHandle toEntityHandle;

    ColumnConverter(Class<?> converterType,
                    Class<?> entityAttributeType,
                    Class<?> databaseColumnType,
                    MethodHandle toDatabaseHandle,
                    MethodHandle toEntityHandle) {
        this.converterType = Objects.requireNonNull(converterType, "converterType must not be null");
        this.entityAttributeType = Objects.requireNonNull(entityAttributeType, "entityAttributeType must not be null");
        this.databaseColumnType = Objects.requireNonNull(databaseColumnType, "databaseColumnType must not be null");
        this.toDatabaseHandle = Objects.requireNonNull(toDatabaseHandle, "toDatabaseHandle must not be null");
        this.toEntityHandle = Objects.requireNonNull(toEntityHandle, "toEntityHandle must not be null");
    }

    public Class<?> converterType() {
        return converterType;
    }

    public Class<?> entityAttributeType() {
        return entityAttributeType;
    }

    public Class<?> databaseColumnType() {
        return databaseColumnType;
    }

    public @Nullable Object toDatabase(@Nullable Object attributeValue) {
        try {
            return (Object) toDatabaseHandle.invokeExact(attributeValue);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new DbException("Failed to convert attribute value to database column via ["
                    + converterType.getName() + "]", t);
        }
    }

    /**
     * 条件表达式或弱类型字符串列入口专用：若传入值已是目标数据库列类型且非实体属性类型，
     * 直接透传避免二次转换引发 {@link ClassCastException}；否则走 {@link #toDatabase(Object)}。
     */
    public @Nullable Object toDatabaseIfAttribute(@Nullable Object value) {
        if (value == null) {
            return toDatabase(null);
        }
        if (entityAttributeType != Object.class
                && !entityAttributeType.isInstance(value)
                && databaseColumnType != Object.class
                && databaseColumnType.isInstance(value)) {
            return value;
        }
        return toDatabase(value);
    }

    public @Nullable Object toEntity(@Nullable Object databaseValue) {
        try {
            return (Object) toEntityHandle.invokeExact(databaseValue);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new DbException("Failed to convert database column value to entity attribute via ["
                    + converterType.getName() + "]", t);
        }
    }
}
