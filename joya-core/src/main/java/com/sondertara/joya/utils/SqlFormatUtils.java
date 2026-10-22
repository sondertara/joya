package com.sondertara.joya.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 参数占位符、表别名与列名格式工具。
 *
 * @author huangxiaohu
 */
public final class SqlFormatUtils {

    public static final String POSITIONAL_OR_INDEXED_PARAMETER = "\\?(\\d+(?![#\\w]))";

    /**
     * ?[digit]
     */
    public static final Pattern NUMBERED_STYLE_PARAM = Pattern.compile(POSITIONAL_OR_INDEXED_PARAMETER);

    /**
     * ?[no digit]
     */
    public static final Pattern JDBC_STYLE_PARAM = Pattern.compile("\\?(?!\\d)");

    /**
     * 带表别名的列（如 t0.user_name）
     */
    public static final Pattern COLUMN_WITH_TABLE_ALIAS = Pattern.compile("(?<![\\w\"'])[tT][0-9]+\\.[A-Za-z0-9_]+");

    private SqlFormatUtils() {
        throw new IllegalStateException("Utility class cannot be instantiated");
    }

    /**
     * 将 SQL 片段中带表别名的驼峰列名转换为下划线风格。
     */
    public static String underlineColumn(String sql) {
        if (Strings.isBlank(sql)) {
            return "";
        }
        return COLUMN_WITH_TABLE_ALIAS.matcher(sql).replaceAll(m -> Strings.toUnderlineCase(m.group()));
    }

    public static String getJdbcStyleSql(String sql) {
        if (NUMBERED_STYLE_PARAM.matcher(sql).find()) {
            return sql.replaceAll(POSITIONAL_OR_INDEXED_PARAMETER, "?");
        }
        return sql;
    }

    /**
     * 剥离条件 SQL 中的表别名前缀（{@code t0.column} → {@code column}）。
     */
    public static String stripTableAlias(String sql) {
        if (Strings.isBlank(sql)) {
            return sql;
        }
        return COLUMN_WITH_TABLE_ALIAS.matcher(sql).replaceAll(m -> {
            String group = m.group();
            int dot = group.indexOf('.');
            return dot >= 0 ? group.substring(dot + 1) : group;
        });
    }

    public static boolean isPrimitive(Class<?> propertyType) {
        return propertyType != null
                && (propertyType.isPrimitive()
                || TypeUtils.isNumber(propertyType)
                || ClassUtils.isPrimitiveWrapper(propertyType));
    }
}
