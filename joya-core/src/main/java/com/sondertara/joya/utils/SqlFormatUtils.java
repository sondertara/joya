package com.sondertara.joya.utils;

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.sondertara.common.reflect.ClassUtils;
import com.sondertara.common.regex.PatternPool;
import com.sondertara.common.text.CharUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.regex.RegexUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.query.ColumnPair;
import com.sondertara.joya.core.query.NativeSqlQuery;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * @author huangxiaohu
 */
@Slf4j
public class SqlFormatUtils {

    public static final String POSITIONAL_OR_INDEXED_PARAMETER = "\\?(\\d+(?![#\\w]))";
    /**
     * ?[digit]<space> or ?[digit]<space> end
     */
    public static final Pattern NUMBERED_STYLE_PARAM = Pattern.compile(POSITIONAL_OR_INDEXED_PARAMETER);

    /**
     * ?[no digit]
     */
    public static final Pattern JDBC_STYLE_PARAM = Pattern.compile("\\?(?!\\d)");


    /**
     * 左边字段提取
     */
    public static final Pattern LEFT_COLUMN_FORMAT = Pattern.compile("^[t|T][0-9]+(.)[A-Za-z0-9]+[\\s]*[=]?|[\\s][t|T][0-9]+(.)[A-Za-z0-9]+[\\s]*[=]?");
    /**
     * 带表别名的列
     */
    public static final Pattern COLUMN_WITH_TABLE_ALIAS = Pattern.compile("(?![\"'])[t|T][0-9]+(.)[A-Za-z0-9]+[\\s]*(?![\"'])");

    public static final Pattern AS_PATTERN = PatternPool.get("\\s+[Aa][Ss]\\s+");

    private SqlFormatUtils() {
        throw new IllegalStateException("工具类,不需要实例化");
    }

    /**
     * 根据查询列表SQL语句自动构造查询记录总数的SQL语句
     *
     * @param strSql String
     * @return String
     */
    public static String buildCountSql(Object strSql) {

        StringBuilder countBuff = new StringBuilder();
        if (strSql != null) {
            String sql;
            if (strSql instanceof String) {
                sql = (String) strSql;
            } else if (strSql instanceof StringBuffer) {
                sql = ((StringBuffer) strSql).toString();
            } else {
                throw new IllegalArgumentException("不受支持的参数类型!");
            }

            if (containsDistinctKeywords(sql)) {

                // 查询字段
                String queryField = sql.substring(findStrPosition(sql, "distinct") + 8, findStrPosition(sql, "from")).trim();

                countBuff.append("select count(distinct ").append(queryField).append(") ");
            } else {
                countBuff.append("select count(*) ");
            }

            countBuff.append(removeOrderBy(trimFrom(sql)));
        }
        return countBuff.toString();
    }

    /**
     * 取sql语句从"from"之后的字符串
     *
     * @param sql String
     * @return String
     */
    public static String trimFrom(String sql) {
        String patternString = "[Ff][Rr][Oo][Mm]";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(sql);
        // 后面的字符
        return matcher.find() ? sql.substring(matcher.start()) : "";
    }

    /**
     * 判断SQL语句中是否包含distinct关键字
     *
     * @param sql str
     * @return is contains distinct
     */
    public static boolean containsDistinctKeywords(String sql) {

        Pattern pattern = Pattern.compile("\\s*" + buildRegexStr("select") + "\\s+" + buildRegexStr("distinct"));
        Matcher matcher = pattern.matcher(sql);

        return matcher.find() && matcher.start() == 0;
    }

    /**
     * 根据字符串生成正则表达式 比如where生成[Ww][Hh][Ee][Rr]的正则表达式
     *
     * @param str String
     * @return String
     */
    public static String buildRegexStr(String str) {

        // 转出大写
        String upperCaseStr = str.toUpperCase();

        char[] strArr = upperCaseStr.toCharArray();
        char[] regexArr = new char[str.length() * 4];
        for (int i = 0; i < strArr.length; i++) {
            regexArr[4 * i] = '[';
            regexArr[4 * i + 1] = strArr[i];
            // to lower case
            regexArr[4 * i + 2] = (char) (strArr[i] + 32);
            regexArr[4 * i + 3] = ']';
        }

        return String.copyValueOf(regexArr);
    }

    /**
     * 替换不规则的order by 子句为" ORDER BY "
     *
     * @param sql String
     * @return String
     */
    public static String replaceOrderBy(String sql) {
        String patternString = "\\s+[Oo][Rr][Dd][Ee][Rr]\\s+[Bb][Yy]\\s+";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(sql);
        // 后面的字符
        return matcher.replaceAll(" ORDER BY ");
    }

    /**
     * 替换不规则的AA 子句为" AS "
     *
     * @param sql String
     * @return String
     */
    public static String replaceAs(String sql) {
        Matcher matcher = AS_PATTERN.matcher(sql);
        return matcher.replaceAll(" AS ").trim();
    }

    /**
     * 替换连续空格 为标准一个空格
     *
     * @param sql sql str
     * @return the single space sql srt
     */
    public static String replaceSpace(String sql) {
        String patternString = "\\s*( {2,})\\s*";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(sql);
        return matcher.replaceAll(" ").trim();
    }

    /**
     * 格式化列,只格式化左边部分
     *
     * @param sql str
     * @return underLine str
     */
    public static String formatColumn(String sql) {
        if (StringUtils.isBlank(sql)) {
            return "";
        }

        return RegexUtils.replaceAll(sql, LEFT_COLUMN_FORMAT, m -> {
            String group = m.group();
            return StringUtils.toUnderlineCase(group);
        });
    }

    /**
     * 格式化列
     *
     * @param sql str
     * @return underLine str
     */
    public static String underlineColumn(String sql) {
        if (StringUtils.isBlank(sql)) {
            return "";
        }

        return RegexUtils.replaceAll(sql, COLUMN_WITH_TABLE_ALIAS, m -> {
            String group = m.group();
            return StringUtils.toUnderlineCase(group);
        });
    }

    /**
     * 格式化列
     *
     * @param sql str
     * @return underLine str
     */
    public static String underlineColumn1(String sql) {
        if (StringUtils.isBlank(sql)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String trim = sql.trim();
        int index = trim.indexOf(" ");
        int count = StringUtils.count(trim, StringUtils.SPACE);
        if (count == 0) {
            sb.append(warpColumn(trim));

        } else if (count == 1) {
            String substring = trim.substring(0, index);
            ColumnPair pair = warpColumn(substring);
            sb.append(pair.getKey());
            sb.append(trim.substring(index));
        } else {
            //complex sql str,just append to the end
            sb.append(trim);
        }
        return sb.toString();
    }

    /**
     * 过滤 sql语句中的order by 子句
     *
     * @param sql str
     * @return String
     */
    public static String removeOrderBy(String sql) {
        String patternString = "\\sORDER\\sBY\\s[a-zA-Z0-9._,\\s]+";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(replaceOrderBy(sql));
        return matcher.replaceAll("");
    }

    /**
     * 查找匹配字符串的位置
     *
     * @param sql       str
     * @param targetStr target
     * @return index
     */
    public static int findStrPosition(String sql, String targetStr) {
        String patternString = buildRegexStr(targetStr);
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(sql);

        return matcher.find() ? matcher.start() : -1;
    }


    public static String formatSql(String sql) {
        if (sql == null) {
            return null;
        }
        return SqlFormatter.format(sql);
    }

    public static String formatSql(NativeSqlQuery sql) {
        if (sql == null) {
            return null;
        }
        return sql.toFormattedSql();
    }


    /**
     * format column name
     * <p>
     * t0.userName ->t0.user_name
     * t0.userId as user -> t0.user_id AS user
     *
     * @param columnStr name
     * @return formatted name
     */
    public static ColumnPair warpColumn(String columnStr) {
        if (null == columnStr) {
            return null;
        }

        String columnName = columnStr.trim();
        //check if multiple sql str
        String[] strings = columnName.split(StringUtils.COMMA);
        if (strings.length > 1) {
            StringJoiner sj = new StringJoiner(StringUtils.COMMA + " ");
            for (String part : strings) {
                String column = replaceAs(part);
                StringBuilder sb = new StringBuilder();
                //if complex sql
                List<String> list = RegexUtils.findAllGroup0("^(t)[0-9]+\\.[a-zA-Z_]+", column);
                //复杂sql直接拼接不处理
                if (list.isEmpty()) {
                    return new ColumnPair(columnName, null, false);
                } else {
                    for (String string : list) {
                        int i = column.indexOf(string);
                        sb.append(column, 0, i);
                        //if is underline case,just append
                        if (string.contains("_")) {
                            sb.append(string);
                        } else {
                            // came case to underline
                            String underlineCase = StringUtils.toUnderlineCase(string);
                            underlineCase = JoyaJdbc.config().isColumnUppercase() ? underlineCase.toUpperCase() : underlineCase.toLowerCase();
                            sb.append(underlineCase);
                        }
                        //append the remaining part.
                        sb.append(column.substring(i + string.length()));
                        sj.add(sb);
                    }
                }
            }
            return new ColumnPair(sj.toString(), null, false);
        }

        String column = replaceAs(columnName);
        // 有AS
        int asIndex = column.indexOf(" AS ");

        String asPart = null;
        if (asIndex > 1) {
            asPart = StringUtils.trim(column.substring(asIndex + 4));
            column = StringUtils.trim(column.substring(0, asIndex));
        } else {
            String[] split = column.split(" ");
            if (split.length > 1) {
                asPart = StringUtils.trim(split[1]);
                column = StringUtils.trim(split[0]);
            }
        }
        // 有别名
        int index = column.indexOf(".");
        StringBuilder sb;
        if (index > -1) {
            sb = new StringBuilder(column.substring(0, index));
            sb.append(".");
            String col = column.substring(index + 1);
            if (!col.contains(String.valueOf(CharUtils.UNDERLINE))) {
                col = StringUtils.toUnderlineCase(col);
            }
            col = JoyaJdbc.config().isColumnUppercase() ? col.toUpperCase() : col.toLowerCase();
            sb.append(col);
        } else {
            if (!column.contains(String.valueOf(CharUtils.UNDERLINE))) {
                column = StringUtils.toUnderlineCase(column);
            }
            column = JoyaJdbc.config().isColumnUppercase() ? column.toUpperCase() : column.toLowerCase();
            sb = new StringBuilder(column);
        }
        String string = sb.toString();
        if (string.endsWith("*")) {
            return new ColumnPair(string, asPart, false);
        } else {
            return new ColumnPair(string, asPart);
        }
    }

    public static String getJdbcStyleSql(String sql) {
        if (RegexUtils.contains(NUMBERED_STYLE_PARAM, sql)) {
            return sql.replaceAll(POSITIONAL_OR_INDEXED_PARAMETER, "?");
        } else {
            return sql;
        }
    }

    public static <K, V> Map<V, K> invertMap(final Map<K, V> map) {
        final Map<V, K> out = new HashMap<>(map.size());
        for (final Map.Entry<K, V> entry : map.entrySet()) {
            out.put(entry.getValue(), entry.getKey());
        }
        return out;
    }

    public static boolean isPrimitive(Class<?> propertyType) {

        if (propertyType.isPrimitive()) {
            return true;
        }
        if (Number.class.isAssignableFrom(propertyType)) {
            return true;
        }
        if (ClassUtils.isPrimitiveWrapper(propertyType)) {
            return true;
        }
        try {
            return ((Class<?>) propertyType.getField("TYPE").get(null)).isPrimitive();
        } catch (Exception e) {
            return false;
        }
    }
}
