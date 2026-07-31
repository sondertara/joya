package com.sondertara.joya.core.jdbc.dao.support;

import java.lang.reflect.Method;

public abstract class AbstractQueryParser implements SqlParser<EntityBasedBoundSql> {
    protected final Class<?> domainClass;
    protected final Method statementMethod;

    protected AbstractQueryParser(Class<?> domainClass, Method statementMethod) {
        this.domainClass = domainClass;
        this.statementMethod = statementMethod;
    }

    /**
     * 1 based
     *
     * @return
     */
    public abstract int lastIndex();
}
