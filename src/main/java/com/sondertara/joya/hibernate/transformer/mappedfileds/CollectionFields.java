package com.sondertara.joya.hibernate.transformer.mappedfileds;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 还未完成
 *
 * @author huangxiaohu
 */
public class CollectionFields extends Fields {
    private final Class<?> genericClass;

    public CollectionFields(PropertyDescriptor propertyDescriptor, Class<?> genericClass) {
        super(propertyDescriptor);
        this.genericClass = genericClass;
    }

    @Override
    public boolean isCollection() {
        return true;
    }

    @Override
    public void setResultPropertyValue(BeanWrapper bw, Object instantiate, String name, Object value) {

    }

    @Override
    public Object instantiateObjectValue() {
        Class<?> propertyType = this.propertyDescriptor.getPropertyType();
        if (propertyType.isAssignableFrom(Set.class)) {
            return new HashSet<>();
        } else if (propertyType.isAssignableFrom(List.class)) {
            return new ArrayList<>();
        }
        throw new RuntimeException("只支持两种集合类型：Set,List");
    }

    public Class<?> getGenericClass() {
        return genericClass;
    }


    public Object instantiateGenericObject() {
        if (this.genericClass.isAssignableFrom(Map.class)) {
            return new HashMap<>();
        }
        return BeanUtils.instantiateClass(this.genericClass);
    }

}
