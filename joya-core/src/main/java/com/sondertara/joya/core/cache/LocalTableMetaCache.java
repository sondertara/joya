package com.sondertara.joya.core.cache;

import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.collection.WeakConcurrentMap;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.PersistenceAnnotations;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * local cache for entity table metadata
 *
 * @author huangxiaohu
 */
public class LocalTableMetaCache {

    private static final Logger log = LoggerFactory.getLogger(LocalTableMetaCache.class);

    private final Map<Class<?>, Optional<EntityTableMetaDefinition>> cache;
    private final Map<Class<?>, EntityMetadataContext> contextCache;

    public LocalTableMetaCache() {
        this.cache = new WeakConcurrentMap<>();
        this.contextCache = new WeakConcurrentMap<>();
    }

    private Optional<EntityTableMetaDefinition> getDirect(Class<?> entityClass) {
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
        EntityTableMetaDefinition entityTableMetaDefinition = new EntityTableMetaDefinition();
        entityTableMetaDefinition.setTableName(tableName);
        entityTableMetaDefinition.setSchemaName(schemaName);

        // Scan superclass hierarchy from base class down to subclass so inherited @Id / fields are included
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> cur = entityClass; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            hierarchy.add(0, cur);
        }
        Map<String, Field> fieldMap = new LinkedHashMap<>();
        for (Class<?> clazz : hierarchy) {
            for (Field field : clazz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                fieldMap.put(field.getName(), field);
            }
        }

        LinkedCaseInsensitiveMap<String> relation = new LinkedCaseInsensitiveMap<>();
        LinkedCaseInsensitiveMap<Field> columns = new LinkedCaseInsensitiveMap<>();

        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            Field field = entry.getValue();
            if (PersistenceAnnotations.isTransientPresent(field)) {
                continue;
            }
            String name = field.getName();
            name = Strings.toUnderlineCase(name);
            Optional<String> colName = PersistenceAnnotations.findColumnName(field);
            if (colName.isPresent()) {
                name = colName.get();
            }

            name = name.toLowerCase(Locale.US);
            if (PersistenceAnnotations.isIdPresent(field)) {
                entityTableMetaDefinition.setPrimaryKey(name);
                entityTableMetaDefinition.setPrimaryKeyType(field.getType());
            }
            columns.put(name, field);
            relation.put(name, field.getName());
        }

        entityTableMetaDefinition.setEntityClass(entityClass);
        entityTableMetaDefinition.setColumns(columns);
        entityTableMetaDefinition.setColumnFieldNameRelation(relation);
        return Optional.of(entityTableMetaDefinition);
    }

    public Optional<EntityTableMetaDefinition> findByEntityClass(Class<?> entityClass) {
        return cache.computeIfAbsent(entityClass, this::getDirect).map(EntityTableMetaDefinition::clone);
    }

    public EntityMetadataContext getMetadataContext(Class<?> entityClass) {
        return contextCache.computeIfAbsent(entityClass, cls -> {
            Optional<EntityTableMetaDefinition> def = cache.computeIfAbsent(cls, this::getDirect);
            return EntityMetadataContext.from(def.orElseThrow(() ->
                    new IllegalArgumentException("No table metadata registered for entity: " + cls.getName())));
        });
    }

    public void destroy() {
        this.cache.clear();
        this.contextCache.clear();
    }
}
