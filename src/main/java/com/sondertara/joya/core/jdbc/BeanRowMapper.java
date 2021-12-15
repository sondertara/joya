package com.sondertara.joya.core.jdbc;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.String;

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
            for (int i = 1; i <= count; i++) {
                PropertyDescriptor pd = new PropertyDescriptor((String)row.getColumnLabel(i), type);
                Method setter = pd.getWriteMethod();
                setter.invoke(bean, row.getObject(i));
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
