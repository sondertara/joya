package com.sondertara.joya.core.jdbc.dao.support;

import java.lang.reflect.Method;

/**
 * @author huangxiaohu
 */
public interface SqlConveror {
    /**
     *
     * @param domainClass
     * @param method
     * @param args
     * @return
     */
    String convert(Class<?> domainClass, Method method, Object[] args);
}
