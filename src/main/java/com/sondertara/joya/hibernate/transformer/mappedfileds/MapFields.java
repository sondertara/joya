package com.sondertara.joya.hibernate.transformer.mappedfileds;

import org.springframework.beans.BeanWrapper;

import java.beans.PropertyDescriptor;
import java.util.HashMap;
import java.util.Map;

/**
 * @author SonderTara
 */
public class MapFields extends Fields {
    public MapFields(PropertyDescriptor propertyDescriptor) {
        super(propertyDescriptor);
    }


    @Override
    public boolean isMap() {
        return true;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setResultPropertyValue(BeanWrapper bw, Object instantiate, String name, Object value) {
        Map map = (Map) instantiate;
        map.put(name, value);
    }

    @Override
    public Object instantiateObjectValue() {
        return new HashMap<>();
    }


}
