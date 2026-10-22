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

    public static boolean detectIsQuery(String sql) {
        if (sql == null) {
            return true;
        }
        String normalized = stripLeadingComments(sql).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        return !(normalized.startsWith("update")
                || normalized.startsWith("delete")
                || normalized.startsWith("insert")
                || normalized.startsWith("truncate")
                || normalized.startsWith("drop")
                || normalized.startsWith("alter")
                || normalized.startsWith("create")
                || normalized.startsWith("merge")
                || normalized.startsWith("replace"));
    }

    /**
     * 剥离 SQL 前导空白、块注释（{@code /* ... *}{@code /}）与行注释（{@code -- ...}）。
     */
    public static String stripLeadingComments(String sql) {
        if (sql == null) {
            return "";
        }
        int len = sql.length();
        int i = 0;
        while (i < len) {
            while (i < len && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
                continue;
            }
            if (i + 1 < len && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i + 2);
                if (nl < 0) {
                    return "";
                }
                i = nl + 1;
                continue;
            }
            break;
        }
        return sql.substring(i).trim();
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
