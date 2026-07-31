package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.support.annotation.Query;

import java.lang.reflect.Method;

/**
 * @author huangxiaohu
 */
public class DefaultSqlConvert implements SqlConveror {

    private static final String WORD_FINDER = "(([A-Z]?[a-z0-9]+)|([A-Z]))";


    @Override
    public String convert(Class<?> domainClass, Method method, Object[] args) {
        Query query = method.getAnnotation(Query.class);
        if (null != query) {

        }
        return null;
    }
}
