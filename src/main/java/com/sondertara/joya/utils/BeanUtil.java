package com.sondertara.joya.utils;

import com.sondertara.common.util.CollectionUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.util.Assert;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bean属性拷贝
 * Bean深度转换
 * Bean深度转换
 *
 * @author huangxiaohu
 */
public abstract class BeanUtil extends org.springframework.beans.BeanUtils {

    /**
     * 修改spring的BeanUtils,不用null覆盖已有的值
     */
    public static void copyProperties(Object source, Object target) throws BeansException {
        Assert.notNull(source, "Source must not be null");
        Assert.notNull(target, "Target must not be null");
        Class<?> clazz = target.getClass();
        PropertyDescriptor[] targetProps = getPropertyDescriptors(clazz);
        for (PropertyDescriptor targetProp : targetProps) {
            if (targetProp.getWriteMethod() != null) {
                PropertyDescriptor sourceProp = getPropertyDescriptor(source.getClass(), targetProp.getName());
                if (sourceProp != null && sourceProp.getReadMethod() != null) {
                    try {
                        Method readMethod = sourceProp.getReadMethod();
                        if (!Modifier.isPublic(readMethod.getDeclaringClass().getModifiers())) {
                            readMethod.setAccessible(true);
                        }
                        Object value = readMethod.invoke(source);
                        // 这里判断以下value是否为空 当然这里也能进行一些特殊要求的处理 例如绑定时格式转换等等
                        if (value != null) {
                            Method writeMethod = targetProp.getWriteMethod();
                            if (!Modifier.isPublic(writeMethod.getDeclaringClass().getModifiers())) {
                                writeMethod.setAccessible(true);
                            }
                            writeMethod.invoke(target, value);
                        }
                    } catch (Throwable ex) {
                        throw new FatalBeanException("Could not copy properties from source to target", ex);
                    }
                }
            }
        }
    }


    /**
     * Bean to Map
     */
    public static <T> Map<String, Object> beanToMap(T bean) {
        Map<String, Object> map = new HashMap<>(16);
        if (bean != null) {
            BeanMap beanMap = BeanMap.create(bean);

            for (Object key : beanMap.keySet()) {
                if (beanMap.get(key) != null) {
                    map.put(key.toString(), beanMap.get(key));
                }
            }
        }
        return map;
    }

    /**
     * Map --> Bean
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T mapToBean(Map map, T t) {
        BeanMap beanMap = BeanMap.create(t);
        beanMap.putAll(map);
        return (T) beanMap.getBean();
    }

    public static <T> List<Map<String, Object>> beansToMaps(List<T> beanList) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (beanList == null || beanList.size() == 0) {
            return null;
        }
        for (T bean : beanList) {
            if (bean != null) {
                Map<String, Object> beanToMaps = beanToMap(bean);
                maps.add(beanToMaps);
            }
        }
        return maps;
    }

    public static <T> List<T> mapsToBeans(List<Map<String, Object>> mapList, Class<T> t) {
        List<T> beans = new ArrayList<>();
        if (CollectionUtils.isEmpty(mapList)) {
            return null;
        }
        for (Map<String, Object> map : mapList) {
            T t1 = null;
            try {
                t1 = t.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
            T t2 = mapToBean(map, t1);
            beans.add(t2);
        }
        return beans;
    }

}
