package com.sondertara.joya.core.cache;

import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.Strings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 实体表元数据本地缓存 —— 单轨直接产出不可变 {@link EntityMetadataContext}，
 * 基于 JDK {@link ClassValue} 实现无锁高频访问与随 {@link ClassLoader} 自动回收。
 *
 * @author huangxiaohu
 */
public class LocalTableMetaCache {

    private volatile ClassValue<EntityMetadataContext> contextCache = createCache();

    private static ClassValue<EntityMetadataContext> createCache() {
        return new ClassValue<>() {
            @Override
            protected EntityMetadataContext computeValue(Class<?> entityClass) {
                return parseMetadataContext(entityClass);
            }
        };
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

        tableName = tableName.toLowerCase(Locale.US);
        EntityMetadataContext.Builder builder = new EntityMetadataContext.Builder(entityClass)
                .tableName(tableName)
                .schemaName(schemaName);

        // Scan superclass hierarchy from base class down to subclass so inherited @Id / fields are included
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> cur = entityClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
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
            String colName = PersistenceAnnotations.findColumnName(field)
                    .orElseGet(() -> Strings.toUnderlineCase(field.getName()));
            colName = colName.toLowerCase(Locale.US);
            if (PersistenceAnnotations.isIdPresent(field)) {
                builder.primaryKey(colName)
                        .primaryKeyType(field.getType())
                        .generatedColumn(colName);
            }
            builder.column(colName, field.getName(), field);
        }

        return builder.build();
    }

    public EntityMetadataContext getMetadataContext(Class<?> entityClass) {
        return contextCache.get(entityClass);
    }

    public void destroy() {
        this.contextCache = createCache();
    }
}
