package com.sondertara.joya.utils;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.util.Collection;

/**
 * Bean 属性工具。
 *
 * @author huangxiaohu
 */
public final class PropertyUtils {

    private PropertyUtils() {
    }

    public static Collection<PropertyDescriptor> getPropertyDescriptors(Class<?> clazz) {
        try {
            return java.util.Arrays.asList(java.beans.Introspector.getBeanInfo(clazz).getPropertyDescriptors());
        } catch (IntrospectionException e) {
            throw new IllegalStateException("Failed to introspect bean: " + clazz.getName(), e);
        }
    }
}