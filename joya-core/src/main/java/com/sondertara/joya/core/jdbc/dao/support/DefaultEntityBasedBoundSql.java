package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;

import java.lang.reflect.Type;
import java.util.List;

public class DefaultEntityBasedBoundSql implements EntityBasedBoundSql {

    private final Type returnType;

    private final String preparedSql;

    private final List<JoyaExecutorSupport.ParameterBinding> parameters;

    public DefaultEntityBasedBoundSql(Type returnType, String preparedSql, List<JoyaExecutorSupport.ParameterBinding> parameters) {
        this.returnType = returnType;
        this.preparedSql = preparedSql;
        this.parameters = parameters;
    }

    @Override
    public boolean isQuery() {
        String sql = this.preparedSql.toLowerCase().trim();
        if (sql.toLowerCase().startsWith("update") || sql.startsWith("delete")) {
            return false;
        }

        if (sql.startsWith("truncate") || sql.startsWith("drop")) {
            return false;
        }
        return true;
    }

    @Override
    public List<JoyaExecutorSupport.ParameterBinding> parameterBinding() {
        return this.parameters;
    }

    @Override
    public Type returnType() {
        return this.returnType;
    }

    @Override
    public String getSqlString() {
        return this.preparedSql;
    }

}
