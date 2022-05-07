package com.sondertara.joya.core.jdbc;

import com.sondertara.common.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;

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
                //the jdk bug,don`t remove the String convert.
                PropertyDescriptor pd = new PropertyDescriptor((String) StringUtils.toCamelCase(row.getColumnLabel(i)), type);
                Method setter = pd.getWriteMethod();
                setter.invoke(bean, row.getObject(i));
            }
            return bean;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
