package com.sondertara.joya.hibernate.transformer;

import com.google.common.collect.Maps;
import com.sondertara.joya.hibernate.transformer.mappedfileds.Fields;
import com.sondertara.joya.hibernate.transformer.mappedfileds.MapFields;
import org.springframework.beans.BeanUtils;

import java.beans.PropertyDescriptor;
import java.util.Date;
import java.util.Map;

/**
 * @author huangxiaohu
 */
public class MappedFieldsInitializer {

    public Map<String, Fields> init(Class<?> mappedClass) {
        Map<String, Fields> fields = Maps.newHashMap();
        PropertyDescriptor[] pds = BeanUtils.getPropertyDescriptors(mappedClass);
        for (PropertyDescriptor pd : pds) {
            if (pd.getWriteMethod() == null) {
                continue;
            }

            Class<?> propertyType = pd.getPropertyType();
            String name = pd.getName();

            if (isPrimitive(propertyType)) {
                fields.put(name, new Fields(pd));
                continue;
            }

            if (isMap(propertyType)) {
                fields.put(name, new MapFields(pd));
                continue;
            }

            Fields childField = new Fields(pd);

            childField.setChildrenFields(init(propertyType));
            fields.put(name, childField);
        }
        return fields;
    }

    private static boolean isMap(Class<?> propertyType) {
        return Map.class.isAssignableFrom(propertyType);
    }

    private static boolean isPrimitive(Class<?> propertyType) {
        return Number.class.isAssignableFrom(propertyType) ||
                propertyType.isPrimitive() ||
                String.class.isAssignableFrom(propertyType) ||
                Date.class.isAssignableFrom(propertyType);
    }

}
