package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport;
import com.sondertara.joya.core.kernel.dialect.DbType;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

public class DefaultEntityBasedBoundSql implements EntityBasedBoundSql {

    private static final Runnable NO_OP_POST_UPDATE = () -> {
    };

    private final Type returnType;
    private final String preparedSql;
    private final List<JoyaExecutorSupport.ParameterBinding> parameters;
    private final boolean query;
    private final boolean cacheable;
    private final OptionalInt maxResults;
    private final Runnable postUpdateAction;

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters) {
        this(returnType, preparedSql, parameters, true, OptionalInt.empty(), null, null);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            @Nullable DbType dbType) {
        this(returnType, preparedSql, parameters, true, OptionalInt.empty(), null, dbType);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            boolean cacheable) {
        this(returnType, preparedSql, parameters, cacheable, OptionalInt.empty(), null, null);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            boolean cacheable,
            OptionalInt maxResults) {
        this(returnType, preparedSql, parameters, cacheable, maxResults, null, null);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            boolean cacheable,
            OptionalInt maxResults,
            Runnable postUpdateAction) {
        this(returnType, preparedSql, parameters, cacheable, maxResults, postUpdateAction, null);
    }

    public DefaultEntityBasedBoundSql(
            Type returnType,
            String preparedSql,
            List<JoyaExecutorSupport.ParameterBinding> parameters,
            boolean cacheable,
            OptionalInt maxResults,
            Runnable postUpdateAction,
            @Nullable DbType dbType) {
        this.returnType = returnType;
        this.preparedSql = preparedSql;
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.query = detectIsQuery(preparedSql, dbType);
        this.cacheable = cacheable;
        this.maxResults = maxResults == null ? OptionalInt.empty() : maxResults;
        this.postUpdateAction = postUpdateAction == null ? NO_OP_POST_UPDATE : postUpdateAction;
    }

    public static boolean detectIsQuery(String sql) {
        return detectIsQuery(sql, null);
    }

    public static boolean detectIsQuery(String sql, @Nullable DbType dbType) {
        if (sql == null) {
            return true;
        }
        String normalized = stripLeadingComments(sql, dbType).toLowerCase(Locale.ROOT);
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
     * 剥离 SQL 前导空白、块注释（{@code /* ... *}{@code /}）与行注释（{@code -- ...} 及非 SQL Server 方言下的 {@code # ...}）。
     */
    public static String stripLeadingComments(String sql) {
        return stripLeadingComments(sql, null);
    }

    public static String stripLeadingComments(String sql, @Nullable DbType dbType) {
        if (sql == null) {
            return "";
        }
        boolean allowHashComment = dbType == null || !dbType.isLikeSqlServer();
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
            if (allowHashComment && i < len && sql.charAt(i) == '#') {
                int nl = sql.indexOf('\n', i + 1);
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
    public OptionalInt maxResults() {
        return this.maxResults;
    }

    @Override
    public void postUpdate() {
        this.postUpdateAction.run();
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
