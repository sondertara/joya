package com.sondertara.joya.core.cache;


import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.collection.WeakConcurrentMap;
import com.sondertara.common.lifecycle.Destroyable;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.exceptions.DbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


/**
 * local cache for five min
 * <p>
 * key is class or table name lowercase
 *
 * @author huangxiaohu
 */
public class LocalTableMetaCache implements Destroyable {

    private static final Logger log = LoggerFactory.getLogger(LocalTableMetaCache.class);


    private final Map<Class<?>, Optional<EntityTableMetaDefinition>> cache;

    public LocalTableMetaCache() {
        this.cache = new WeakConcurrentMap<>();

    }

    private Optional<EntityTableMetaDefinition> getDirect(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        String tableName = null;
        String schemaName = null;
        if (null != table) {
            tableName = table.name();
            schemaName = StringUtils.isBlank(table.schema()) ? table.catalog() : table.schema();

        } else {
            Entity entity = entityClass.getAnnotation(Entity.class);
            if (null != entity) {
                tableName = entity.name();
            }
        }
        if (null == tableName) {
            throw new DbException("No [@Table] or [@Entity] annotation found for class->" + entityClass.getName());
        }

        tableName = tableName.toLowerCase(Locale.US);
        EntityTableMetaDefinition entityTableMetaDefinition = new EntityTableMetaDefinition();
        entityTableMetaDefinition.setTableName(tableName);
        entityTableMetaDefinition.setSchemaName(schemaName);
        Map<String, Field> fieldMap = ReflectUtils.getFieldMap(entityClass);
        LinkedCaseInsensitiveMap<String> relation = new LinkedCaseInsensitiveMap<>();
        LinkedCaseInsensitiveMap<Field> columns = new LinkedCaseInsensitiveMap<>();

        for (Map.Entry<String, Field> entry : fieldMap.entrySet()) {
            Field field = entry.getValue();
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            String name = field.getName();
            name = StringUtils.toUnderlineCase(name);
            Column column = field.getAnnotation(Column.class);
            if (null != column && StringUtils.isNotBlank(column.name())) {
                name = column.name();
            }

            name = name.toLowerCase(Locale.US);
            Id id = field.getAnnotation(Id.class);
            if (null != id) {
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


    @Override
    public void destroy() {
        this.cache.clear();
    }
}
