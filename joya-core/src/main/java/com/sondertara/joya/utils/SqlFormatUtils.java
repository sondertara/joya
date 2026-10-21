package com.sondertara.joya.utils;

import com.sondertara.common.regex.PatternPool;
import com.sondertara.joya.core.query.ColumnPair;

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

    private static final Pattern ALIASED_COLUMN_PREFIX = Pattern.compile("^[tT][0-9]+\\.[a-zA-Z0-9_]+");

    public static final Pattern AS_PATTERN = PatternPool.get("\\s+[Aa][Ss]\\s+");

    private SqlFormatUtils() {
        throw new IllegalStateException("Utility class cannot be instantiated");
    }

    /**
     * 规范化 AS 子句为 {@code " AS "}。
     */
    public static String replaceAs(String sql) {
        Matcher matcher = AS_PATTERN.matcher(sql);
        return matcher.replaceAll(" AS ").trim();
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

    /**
     * 格式化列名（如 {@code t0.userName} -> {@code t0.user_name}，{@code t0.userId as user} -> {@code t0.user_id AS user}）。
     */
    public static ColumnPair warpColumn(String columnStr) {
        return warpColumn(columnStr, false);
    }

    /**
     * 格式化列名（支持按配置转为大写列名）。
     */
    public static ColumnPair warpColumn(String columnStr, boolean columnUppercase) {
        if (null == columnStr) {
            return null;
        }

        String columnName = columnStr.trim();
        String[] strings = columnName.split(Strings.COMMA);
        if (strings.length > 1) {
            StringJoiner sj = new StringJoiner(Strings.COMMA + " ");
            for (String part : strings) {
                String column = replaceAs(part);
                List<String> list = new ArrayList<>();
                Matcher fm = ALIASED_COLUMN_PREFIX.matcher(column);
                while (fm.find()) {
                    list.add(fm.group());
                }
                if (list.isEmpty()) {
                    return new ColumnPair(columnName, null, false);
                }
                for (String string : list) {
                    StringBuilder sb = new StringBuilder();
                    int i = column.indexOf(string);
                    sb.append(column, 0, i);
                    if (string.contains("_")) {
                        sb.append(string);
                    } else {
                        String underlineCase = Strings.toUnderlineCase(string);
                        underlineCase = columnUppercase
                                ? underlineCase.toUpperCase()
                                : underlineCase.toLowerCase();
                        sb.append(underlineCase);
                    }
                    sb.append(column.substring(i + string.length()));
                    sj.add(sb);
                }
            }
            return new ColumnPair(sj.toString(), null, false);
        }

        String column = replaceAs(columnName);
        int asIndex = column.indexOf(" AS ");

        String asPart = null;
        if (asIndex > 1) {
            asPart = Strings.trim(column.substring(asIndex + 4));
            column = Strings.trim(column.substring(0, asIndex));
        } else {
            String[] split = column.split(" ");
            if (split.length > 1) {
                asPart = Strings.trim(split[1]);
                column = Strings.trim(split[0]);
            }
        }
        int index = column.indexOf(".");
        StringBuilder sb;
        if (index > -1) {
            sb = new StringBuilder(column.substring(0, index));
            sb.append(".");
            String col = column.substring(index + 1);
            if (!col.contains("_")) {
                col = Strings.toUnderlineCase(col);
            }
            col = columnUppercase ? col.toUpperCase() : col.toLowerCase();
            sb.append(col);
        } else {
            if (!column.contains("_")) {
                column = Strings.toUnderlineCase(column);
            }
            column = columnUppercase ? column.toUpperCase() : column.toLowerCase();
            sb = new StringBuilder(column);
        }
        String string = sb.toString();
        if (string.endsWith("*")) {
            return new ColumnPair(string, asPart, false);
        }
        return new ColumnPair(string, asPart);
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
