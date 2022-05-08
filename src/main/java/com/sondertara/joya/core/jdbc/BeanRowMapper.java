package com.sondertara.joya.core.jdbc;

import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.model.TableDTO;
import com.sondertara.joya.core.model.TableEntity;
import com.sondertara.joya.utils.ClassUtils;

import javax.persistence.Entity;
import javax.persistence.Table;
import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                tableEntity = ClassUtils.getTable(bean, false);
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
                } else {
                    value = row.getObject(i);
                }
                Method setter = pd.getWriteMethod();
                setter.invoke(bean, value);
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
