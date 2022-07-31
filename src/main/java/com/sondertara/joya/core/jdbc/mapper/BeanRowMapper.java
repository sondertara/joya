package com.sondertara.joya.core.jdbc.mapper;

import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.cache.TableClassCache;
import com.sondertara.joya.core.jdbc.Row;
import com.sondertara.joya.core.model.TableEntity;

import javax.persistence.Entity;
import javax.persistence.Table;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * 将一行数据转换成JavaBean
 *
 * @author huangxiaohu
 */
public class BeanRowMapper<T> implements RowMapper<T> {
    private final Class<T> type;

    public BeanRowMapper(Class<T> type) {
        this.type = type;
    }

    @Override
    public T map(Row row) {
        try {
            int count = row.getColumnCount();
            T bean = type.getDeclaredConstructor().newInstance();
            TableEntity tableEntity = null;
            if (type.isAnnotationPresent(Table.class) || type.isAnnotationPresent(Entity.class)) {
                tableEntity = TableClassCache.getInstance().getTable(bean, false);
            }
            for (int i = 1; i <= count; i++) {
                String propertyName;
                if (null != tableEntity) {
                    propertyName = tableEntity.getRelation().get(row.getColumnLabel(i).toLowerCase());
                } else {
                    propertyName = StringUtils.toCamelCase(row.getColumnLabel(i));
                }
                //the jdk bug,don`t remove the String convert.
                PropertyDescriptor pd = new PropertyDescriptor((String) propertyName, type);
                Class<?> propertyType = pd.getPropertyType();
                Object value;
                if (Number.class.isAssignableFrom(propertyType)) {
                    value = propertyType.getConstructor(String.class).newInstance(row.getObject(i).toString());
                } else if (propertyType.equals(Boolean.class) || propertyType.equals(boolean.class)) {
                    value = row.getBoolean(i);
                } else if (Date.class.equals(propertyType)) {
                    value = row.getDate(i);
                } else {
                    value = row.getObject(i);
                }
                Method setter = pd.getWriteMethod();
                if (null != setter) {
                    setter.invoke(bean, value);
                }
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
