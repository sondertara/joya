package com.sondertara.joya.utils;

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.sondertara.common.util.RegexUtils;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.query.NativeSqlQuery;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author huangxiaohu
 */
public class SqlUtils {
    private SqlUtils() {
        throw new IllegalStateException("工具类,不需要实例化");
    }

    /**
     * 字段提取
     */
    public static final Pattern COLUMN_FORMAT = Pattern.compile("^[t|T][0-9]+(.)[A-Za-z0-9]+[\\s]*[=]?|[\\s][t|T][0-9]+(.)[A-Za-z0-9]+[\\s]*[=]?");

    /**
     * 根据查询列表SQL语句自动构造查询记录总数的SQL语句
     *
     * @param strSql String
     * @return String
     */
    public static String buildCountSql(Object strSql) {

        StringBuilder countBuff = new StringBuilder();
        if (strSql != null) {
            String sql = null;
            if (strSql instanceof String) {
                sql = (String) strSql;
            } else if (strSql instanceof StringBuffer) {
                sql = ((StringBuffer) strSql).toString();
            } else {
                throw new IllegalArgumentException("不受支持的参数类型!");
            }

            if (containsDistinctKeywords(sql)) {

                // 查询字段
                String queryField = sql.substring(findStrPosition(sql, "distinct") + 8, findStrPosition(sql, "from"))
                        .trim();

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
     * @param sql
     * @return is contains distinct
     */
    public static boolean containsDistinctKeywords(String sql) {

        StringBuilder patternString = new StringBuilder();
        patternString.append("\\s*").append(buildRegexStr("select")).append("\\s*").append(buildRegexStr("distinct"));

        Pattern pattern = Pattern.compile(patternString.toString());
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
        String patternString = "\\s*[Oo][Rr][Dd][Ee][Rr]\\s+[Bb][Yy]\\s*";
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
        String patternString = "\\s*[Aa][Ss]\\s*";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(sql);
        return matcher.replaceAll(" AS ");
    }

    /**
     * 列格式化为下划线
     *
     * @param sql
     * @return
     */
    public static String underlineColumn(String sql) {
        if (StringUtils.isBlank(sql)) {
            return "";
        }

        return RegexUtils.replaceAll(sql, COLUMN_FORMAT, m -> {
            String group = m.group();
            return StringUtils.toUnderlineCase(group);
        });
    }

    /**
     * 过滤 sql语句中的order by 子句
     *
     * @param sql
     * @return String
     */
    public static String removeOrderBy(String sql) {
        String patternString = "\\sORDER\\sBY\\s[a-zA-Z0-9\\.\\_\\,\\s]+";
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(replaceOrderBy(sql));
        return matcher.replaceAll("");
    }

    /**
     * 查找匹配字符串的位置
     *
     * @param sql
     * @param targetStr
     * @return
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


    public static String warpColumn(String columnName) {
        if (null == columnName) {
            return null;
        }
        String column = replaceAs(columnName);
        // 有AS

        int asIndex = column.indexOf("AS");

        String asPart = null;
        if (asIndex > 1 && asIndex < column.length()) {
            asPart = StringUtils.trim(column.substring(asIndex + 2));
            column = StringUtils.trim(column.substring(0, asIndex - 1));
        }
        // 有别名
        int index = column.indexOf(".");
        if (index > -1) {
            StringBuilder sb = new StringBuilder(column.substring(0, index));
            sb.append(".");
            String col = column.substring(index + 1);
            sb.append(StringUtils.toUnderlineCase(col));
            if (null != asPart) {
                sb.append(" AS ");
                sb.append(asPart);
            }
            return sb.toString();
        } else {
            StringBuilder sb = new StringBuilder(StringUtils.toUnderlineCase(column));
            sb.append(asPart);
            if (null != asPart) {
                sb.append(" AS ");
                sb.append(asPart);
            }
            return sb.toString();
        }
    }
}
