package com.sondertara.joya.utils;

import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.exceptions.JoyaSQLException;
import com.sondertara.joya.core.model.TableDTO;
import com.sondertara.joya.core.model.TableEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author skydu
 */
public class ClassUtils {

    /**
     * @return get current classloader
     */
    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // Cannot access thread context ClassLoader - falling back...
        }
        if (cl == null) {
            // No thread context class loader -> use class loader of this class.
            cl = ClassUtils.class.getClassLoader();
            if (cl == null) {
                // getClassLoader() returning null indicates the bootstrap ClassLoader
                try {
                    cl = ClassLoader.getSystemClassLoader();
                } catch (Throwable ex) {
                    // Cannot access system ClassLoader - oh well, maybe the caller can live with
                    // null...
                }
            }
        }
        return cl;
    }

    /**
     * 获取类的所有字段(包括父类的)
     *
     * @param clazz class
     * @return field
     */
    public static List<Field> getFieldList(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
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
                    fields.add(field);
                }
            } catch (Exception e) {
                throw new JoyaSQLException(e);
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
    public static <T> TableEntity getTable(T bean, boolean readData) {
        Map<String, Object> filedNames = new LinkedHashMap<>();
        Map<String, String> relation = new HashMap<>();
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
            throw new JoyaSQLException("No [@Table] or [@Entity] annotation found for class->" + clazz);
        }

        TableEntity tableDTO = new TableEntity();
        tableDTO.setTableName(tableName);
        for (Class<?> c = clazz; c != Object.class; c = c.getSuperclass()) {
            try {
                Field[] list = c.getDeclaredFields();
                for (Field field : list) {
                    field.setAccessible(true);
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
                    if (readData) {
                        filedNames.put(name, field.get(bean));
                    }
                    relation.put(name, field.getName());
                }
            } catch (Exception e) {
                throw new JoyaSQLException(e);
            }
        }

        tableDTO.setData(filedNames);
        tableDTO.setRelation(relation);
        return tableDTO;
    }


    /**
     * @param clazz
     * @return
     */
    public static Class<?> getSuperClassGenricType(Class<?> clazz) {
        Type genType = clazz.getGenericSuperclass();
        if (!(genType instanceof ParameterizedType)) {
            if (genType instanceof Class) {
                return getSuperClassGenricType((Class<?>) genType);
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
     * @param clazz
     * @param fieldName
     * @return
     */
    public static Field getField(Class<?> clazz, String fieldName) {
        List<Field> fields = getFieldList(clazz);
        for (Field field : fields) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    //
    public static Field getExistedField(Class<?> clazz, String fieldName) {
        List<Field> fields = getFieldList(clazz);
        for (Field field : fields) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        throw new JoyaSQLException("no such field " + fieldName + "/" + clazz.getSimpleName());
    }
}
