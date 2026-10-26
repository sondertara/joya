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

    private static final Pattern ALIASED_COLUMN_TOKEN = Pattern.compile("(?<![\\w\"'])[tT][0-9]+\\.[a-zA-Z0-9_]+");
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

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
        List<String> topLevelParts = splitByTopLevelComma(columnName);
        if (topLevelParts.size() > 1) {
            StringJoiner sj = new StringJoiner(Strings.COMMA + " ");
            for (String part : topLevelParts) {
                String normalized = normalizeTopLevelAs(part.trim());
                sj.add(formatAliasedTokens(normalized, columnUppercase));
            }
            return new ColumnPair(sj.toString(), null, false);
        }

        String column = normalizeTopLevelAs(columnName);
        int asIndex = findTopLevelAsIndex(column);

        String asPart = null;
        if (asIndex > 0) {
            asPart = Strings.trim(column.substring(asIndex + 4));
            column = Strings.trim(column.substring(0, asIndex));
        } else {
            int lastSpace = findLastTopLevelWhitespace(column);
            if (lastSpace > 0) {
                String candidateAlias = Strings.trim(column.substring(lastSpace + 1));
                if (SIMPLE_IDENTIFIER.matcher(candidateAlias).matches()) {
                    asPart = candidateAlias;
                    column = Strings.trim(column.substring(0, lastSpace));
                }
            }
        }

        if (column.indexOf('(') >= 0) {
            String formattedExpr = formatAliasedTokens(column, columnUppercase);
            return new ColumnPair(formattedExpr, asPart, false);
        }

        int index = column.indexOf('.');
        StringBuilder sb;
        if (index > -1) {
            sb = new StringBuilder(column.substring(0, index));
            sb.append('.');
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

    private static String formatAliasedTokens(String expr, boolean columnUppercase) {
        return ALIASED_COLUMN_TOKEN.matcher(expr).replaceAll(m -> {
            String token = m.group();
            int dot = token.indexOf('.');
            String prefix = token.substring(0, dot + 1);
            String col = token.substring(dot + 1);
            if (!col.contains("_")) {
                col = Strings.toUnderlineCase(col);
            }
            col = columnUppercase ? col.toUpperCase() : col.toLowerCase();
            return prefix + col;
        });
    }

    private static List<String> splitByTopLevelComma(String sql) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingleQuote = false;
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            } else if (!inSingleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(sql.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(sql.substring(start));
        return parts;
    }

    private static String normalizeTopLevelAs(String sql) {
        int idx = findTopLevelAsIndex(sql);
        if (idx < 0) {
            return sql.trim();
        }
        return (sql.substring(0, idx).trim() + " AS " + sql.substring(idx + 4).trim()).trim();
    }

    private static int findTopLevelAsIndex(String sql) {
        int depth = 0;
        boolean inSingleQuote = false;
        for (int i = 0; i <= sql.length() - 4; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            } else if (!inSingleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (depth == 0
                        && Character.isWhitespace(c)
                        && (sql.charAt(i + 1) == 'a' || sql.charAt(i + 1) == 'A')
                        && (sql.charAt(i + 2) == 's' || sql.charAt(i + 2) == 'S')
                        && Character.isWhitespace(sql.charAt(i + 3))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findLastTopLevelWhitespace(String sql) {
        int depth = 0;
        boolean inSingleQuote = false;
        int lastIdx = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingleQuote = !inSingleQuote;
            } else if (!inSingleQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')' && depth > 0) {
                    depth--;
                } else if (depth == 0 && Character.isWhitespace(c)) {
                    lastIdx = i;
                }
            }
        }
        return lastIdx;
    }
}
