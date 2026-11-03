package com.sondertara.joya.core.metadata;

import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.Strings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 实体表元数据本地缓存 —— 单轨直接产出不可变 {@link EntityMetadataContext}，
 * 基于 JDK {@link ClassValue} 实现无锁高频访问与随 {@link ClassLoader} 自动回收。
 *
 * <p><b>列顺序契约</b>：实体字段按继承层级（基类在先、子类在后）与字节码字段声明顺序（{@link Class#getDeclaredFields()}）
 * 收集一次并固化为不可变列表，在同一 JVM 生命周期内对所有查询/变更操作保持严格稳定一致。
 *
 * @author huangxiaohu
 */
public final class LocalTableMetaCache {

    private static final ClassValue<EntityMetadataContext> CACHE = new ClassValue<>() {
        @Override
        protected EntityMetadataContext computeValue(Class<?> entityClass) {
            return parseMetadataContext(entityClass);
        }
    };

    private LocalTableMetaCache() {
    }

    private static EntityMetadataContext parseMetadataContext(Class<?> entityClass) {
        Optional<PersistenceAnnotations.TableMeta> tableOpt = PersistenceAnnotations.findTable(entityClass);
        String tableName = null;
        String schemaName = null;
        if (tableOpt.isPresent()) {
            PersistenceAnnotations.TableMeta table = tableOpt.get();
            tableName = table.name();
            schemaName = Strings.isBlank(table.schema()) ? table.catalog() : table.schema();
        } else {
            Optional<String> entityName = PersistenceAnnotations.findEntityName(entityClass);
            if (entityName.isPresent()) {
                tableName = entityName.get();
            }
        }
        if (Strings.isBlank(tableName)) {
            if (tableOpt.isPresent() || PersistenceAnnotations.isEntityPresent(entityClass)) {
                tableName = Strings.toUnderlineCase(entityClass.getSimpleName());
            } else {
                throw new DbException("No [@Table] or [@Entity] annotation found for class->" + entityClass.getName());
            }
        }

        EntityMetadataContext.Builder builder = new EntityMetadataContext.Builder(entityClass)
                .tableName(tableName)
                .schemaName(schemaName);

        // Scan superclass hierarchy from base class down to subclass so inherited @Id / fields are included
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> cur = entityClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            if (PersistenceAnnotations.isIdClassPresent(cur)) {
                throw new DbException("Composite primary key (@IdClass) is not supported on entity ["
                        + entityClass.getName() + "]");
            }
            hierarchy.add(0, cur);
        }
        Map<String, Field> fieldMap = new LinkedHashMap<>();
        for (Class<?> clazz : hierarchy) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                    continue;
                }
                fieldMap.put(field.getName(), field);
            }
        }

        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            Field field = entry.getValue();
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            if (PersistenceAnnotations.isEmbeddedIdPresent(field)) {
                throw new DbException("Composite primary key (@EmbeddedId) on field [" + field.getName()
                        + "] is not supported on entity [" + entityClass.getName() + "]");
            }
            Optional<PersistenceAnnotations.ColumnMeta> columnMeta = PersistenceAnnotations.findColumn(field);
            String colName = columnMeta.map(PersistenceAnnotations.ColumnMeta::name)
                    .filter(Strings::isNotBlank)
                    .orElseGet(() -> Strings.toUnderlineCase(field.getName()));
            if (columnMeta.isPresent()) {
                if (!columnMeta.get().insertable()) {
                    builder.nonInsertableColumn(colName);
                }
                if (!columnMeta.get().updatable()) {
                    builder.nonUpdatableColumn(colName);
                }
            }
            if (PersistenceAnnotations.isIdPresent(field)) {
                if (field.getType().isPrimitive()) {
                    throw new DbException("Primary key field [" + field.getName() + "] on entity ["
                            + entityClass.getName() + "] must use a wrapper type instead of primitive type ["
                            + field.getType().getName() + "]");
                }
                try {
                    builder.primaryKey(colName)
                            .primaryKeyType(field.getType());
                } catch (IllegalStateException e) {
                    throw new DbException(e.getMessage(), e);
                }
            }
            if (PersistenceAnnotations.isGeneratedValuePresent(field)) {
                builder.generatedColumn(colName);
            }
            builder.column(colName, field.getName(), field);
        }

        return builder.build();
    }

    public static EntityMetadataContext getMetadataContext(Class<?> entityClass) {
        return CACHE.get(entityClass);
    }
}
