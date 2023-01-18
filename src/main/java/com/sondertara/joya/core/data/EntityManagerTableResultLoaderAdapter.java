package com.sondertara.joya.core.data;

import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.cache.LocalEntityCache;
import com.sondertara.joya.core.model.TableStructDef;
import com.sondertara.joya.ext.JoyaSpringContext;

import javax.persistence.Column;
import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.Table;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Get the tables with EntityManager
 * you can implement your own adapter,and customize it by use {@link LocalEntityCache#setTableResultAdapter(TableResultLoader)} (TableResultLoader)}
 *
 * @author huangxiaohu
 */
public class EntityManagerTableResultLoaderAdapter implements TableResultLoader {
    @Override

    public List<TableStructDef> load() {
        List<TableStructDef> result = new ArrayList<>();
        EntityManager entityManager = JoyaSpringContext.getBean(EntityManager.class);
        Metamodel metamodel = entityManager.getEntityManagerFactory().getMetamodel();
        for (EntityType<?> entity : metamodel.getEntities()) {
            Class<?> aClass = entity.getJavaType();
            Table annotation = aClass.getAnnotation(Table.class);
            String tableName = Optional.of(annotation).map(Table::name).orElseThrow(() -> new EntityNotFoundException(StringFormatter.format("no entity found for class [{}]", aClass.getName())));
            Map<String, String> fieldNames = new LinkedHashMap<>();
            Field[] fields = aClass.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Column fieldAnnotation = field.getAnnotation(Column.class);
                String columnName = Optional.ofNullable(fieldAnnotation).filter(f -> !StringUtils.isBlank(f.name())).map(f -> StringUtils.toLowerCase(f.name())).orElse(StringUtils.toUnderlineCase(field.getName()));
                fieldNames.put(field.getName(), columnName);
                field.setAccessible(false);
            }
            TableStructDef tableStructDef = new TableStructDef();
            tableStructDef.setClassName(aClass.getName());
            tableStructDef.setTableName(tableName);
            tableStructDef.setFields(fieldNames);
            result.add(tableStructDef);
        }
        return result;
    }

}
