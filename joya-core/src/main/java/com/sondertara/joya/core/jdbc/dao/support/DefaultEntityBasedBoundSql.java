package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;

public class DefaultEntityBasedBoundSql implements EntityBasedBoundSql {

    private final Type returnType;
    private final String preparedSql;
    private final List<JoyaExecutorSupport.ParameterBinding> parameters;
    private final boolean query;
    private final boolean cacheable;

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters) {
        this(returnType, preparedSql, parameters, true);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            boolean cacheable) {
        this.returnType = returnType;
        this.preparedSql = preparedSql;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.query = detectIsQuery(preparedSql);
        this.cacheable = cacheable;
    }

    private static boolean detectIsQuery(String sql) {
        if (sql == null) {
            return true;
        }
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("update")
                || normalized.startsWith("delete")
                || normalized.startsWith("insert")
                || normalized.startsWith("truncate")
                || normalized.startsWith("drop")
                || normalized.startsWith("alter")
                || normalized.startsWith("create"));
    }

    @Override
    public boolean isQuery() {
        return this.query;
    }

    public boolean isCacheable() {
        return this.cacheable;
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
