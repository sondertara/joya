package com.sondertara.joya.cache;

import com.google.common.collect.Maps;
import com.sondertara.common.cache.GuavaAbstractLoadingCache;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.model.TableEntity;
import com.sondertara.joya.ext.JoyaSpringContext;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * @author skydu
 */
public class TableClassCache extends GuavaAbstractLoadingCache<Class<?>, Map<String, Field>> {

    private static volatile TableClassCache cache = null;
    private final DataSource dataSource;

    private TableClassCache(DataSource dataSource) {
        setMaximumSize(1000);
        setExpireAfterWriteDuration(60 * 60);
        this.dataSource = dataSource;
    }


    public synchronized static TableClassCache getInstance() {
        if (null == cache) {
            synchronized (TableClassCache.class) {
                if (null == cache) {
                    DataSource dataSource = JoyaSpringContext.getBean(DataSource.class);
                    cache = new TableClassCache(dataSource);
                }
            }
        }
        return cache;
    }

    /**
     * 获取类的所有字段(包括父类的)
     *
     * @param clazz class
     * @return field
     */
    public static Map<String, Field> getAllFields(Class<?> clazz) {
        Map<String, Field> fields = Maps.newLinkedHashMap();
        Set<String> filedNames = new HashSet<>();
        for (Class<?> c = clazz; c != Object.class; c = c.getSuperclass()) {
            try {
                Field[] list = c.getDeclaredFields();
                for (Field field : list) {
                    String name = field.getName();
                    if (filedNames.contains(name)) {
                        continue;
                    }
                    filedNames.add(field.getName());
                    fields.put(field.getName(), field);
                }
            } catch (Exception e) {
                throw new TaraException(e);
            }
        }
        return fields;
    }


    /**
     * 获取一个实体类对应数据库字段
     *
     * @param bean java pojo
     * @param <T>  the class type of bean
     * @return the table data
     */
    public synchronized <T> TableEntity getTable(T bean, boolean readData) {
        Map<String, Object> filedNames = new LinkedHashMap<>();
        Map<String, String> relation = Maps.newHashMap();
        Class<?> clazz = bean.getClass();
        Table table = clazz.getAnnotation(Table.class);
        String tableName = null;
        if (null != table) {
            tableName = table.name();
        } else {
            Entity entity = clazz.getAnnotation(Entity.class);
            if (null != entity) {
                tableName = entity.name();
            }
        }
        if (null == tableName) {
            throw new TaraException("No [@Table] or [@Entity] annotation found for class->" + clazz);
        }

        Map<String, Class<?>> columnType = new HashMap<>();
        TableEntity tableDTO = new TableEntity();
        tableDTO.setTableName(tableName);
        Optional<Map<String, Field>> optional = get(clazz);
        return optional.map(fields -> {
            for (Field field : fields.values()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.isAnnotationPresent(Transient.class) || field.isAnnotationPresent(org.springframework.data.annotation.Transient.class)) {
                    continue;
                }
                String name = field.getName();
                name = StringUtils.toUnderlineCase(name);
                Column column = field.getAnnotation(Column.class);
                if (null != column) {
                    name = column.name();
                }
                Id id = field.getAnnotation(Id.class);
                if (null != id) {
                    tableDTO.setPrimaryKey(name);
                    tableDTO.setPrimaryKeyType(field.getType());
                }
                if (filedNames.containsKey(name)) {
                    continue;
                }
                columnType.put(name, field.getType());
                if (readData) {
                    try {
                        Object o;
                        if (field.isAccessible()) {
                            o = field.get(bean);
                        } else {
                            field.setAccessible(true);
                            o = field.get(bean);
                            field.setAccessible(false);
                        }
                        filedNames.put(name, o);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                relation.put(name, field.getName());
            }
            tableDTO.setColumnType(columnType);
            tableDTO.setData(filedNames);
            tableDTO.setRelation(relation);
            return tableDTO;
        }).orElseThrow(() -> new TaraException("Get Class definition error"));

    }

    /**
     * @param clazz
     * @return the super class
     */
    public static Class<?> getSuperClassGenericType(Class<?> clazz) {
        Type genType = clazz.getGenericSuperclass();
        if (!(genType instanceof ParameterizedType)) {
            if (genType instanceof Class) {
                return getSuperClassGenericType((Class<?>) genType);
            }
            return Object.class;
        }
        Type[] params = ((ParameterizedType) genType).getActualTypeArguments();
        if (params.length == 0) {
            return Object.class;
        }
        return (Class<?>) params[0];
    }


    /**
     * @param clazz     the class
     * @param fieldName field name
     * @return the current Field
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        Map<String, Field> map = getAllFields(clazz);
        return map.get(fieldName);
    }

    public static Class<?> classForName(String name) throws ClassNotFoundException {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader != null) {
                return classLoader.loadClass(name);
            }
        } catch (Throwable ignore) {
        }
        return Class.forName(name);
    }

    @Override
    protected Optional<Map<String, Field>> fetchData(Class<?> key) {
        return Optional.of(getAllFields(key));
    }

    @Override
    public Optional<Map<String, Field>> get(Class<?> key) {
        return getValue(key);
    }
}
