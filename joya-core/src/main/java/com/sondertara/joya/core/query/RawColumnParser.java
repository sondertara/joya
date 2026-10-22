package com.sondertara.joya.core.query;

import com.sondertara.joya.utils.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生 SQL 列片段解析器 —— 处理手写合成 Lambda（如 {@code u -> "SUM(t0.age)"}）产生的列表达式。
 *
 * <p>职责边界：只解析"字符串形态的列"，不生成 SQL、不感知实体元数据。
 * 该能力原位于 {@code utils.SqlFormatUtils}，但它属于查询层的语义，
 * 放在工具包会让 {@code utils → query} 形成反向依赖，故归位至此。
 */
public final class RawColumnParser {

    private static final Pattern ALIASED_COLUMN_PREFIX = Pattern.compile("^[tT][0-9]+\\.[a-zA-Z0-9_]+");
    private static final Pattern AS_PATTERN = Pattern.compile("\\s+[Aa][Ss]\\s+");

    private RawColumnParser() {
    }

    /**
     * 格式化列名（如 {@code t0.userName} -> {@code t0.user_name}，{@code t0.userId as user} -> {@code t0.user_id AS user}）。
     */
    public static ColumnPair parse(String columnStr) {
        return parse(columnStr, false);
    }

    /**
     * 格式化列名（支持按配置转为大写列名）。
     */
    public static ColumnPair parse(String columnStr, boolean columnUppercase) {
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

    private static String replaceAs(String sql) {
        Matcher matcher = AS_PATTERN.matcher(sql);
        return matcher.replaceAll(" AS ").trim();
    }
}
