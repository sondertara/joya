package com.sondertara.joya.utils;

import com.sondertara.joya.core.kernel.dialect.DbType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * SQL 中的一个有效占位符。{@code index} 为 -1 时表示匿名 {@code ?}，否则为原始的 1 基序号。
     */
    public record Placeholder(int start, int end, int index) {
        public boolean isNumbered() {
            return index >= 0;
        }
    }

    /**
     * 使用通用词法规则查找有效占位符，跳过引号、注释、反引号及 PostgreSQL 美元引号。
     */
    public static List<Placeholder> scanPlaceholders(String sql) {
        return scanPlaceholders(sql, null);
    }

    /**
     * 使用方言感知词法规则查找有效占位符：
     * <ul>
     *   <li>MySQL / MariaDB：支持 {@code #} 行注释与反斜杠转义字符串（如 {@code 'a\' b'}）。</li>
     *   <li>SQL Server / Sybase：{@code #temp} 视为合法临时表标识符而非注释，支持 {@code [...]} 方括号标识符（含 {@code ]]} 转义），字符串按标准 {@code ''} 转义。</li>
     *   <li>PostgreSQL：支持 {@code $tag$...$tag$} 美元引号与 {@code E'...'} 转义字符串，标准字符串反斜杠不转义（兼容 {@code ESCAPE '\'}）。</li>
     *   <li>其他标准 SQL 方言：单引号/双引号仅按重复引号转义，不依赖引号后字符猜测。</li>
     * </ul>
     */
    public static List<Placeholder> scanPlaceholders(String sql, @Nullable DbType dbType) {
        if (sql == null || sql.isEmpty() || sql.indexOf('?') < 0) {
            return List.of();
        }
        boolean genericMode = (dbType == null);
        boolean allowHashComment = genericMode || dbType.isLikeMysql() || dbType == DbType.h2;
        boolean allowBracketQuote = dbType != null && dbType.isLikeSqlServer();
        boolean allowDollarQuote = genericMode || dbType.isLikePostgres() || dbType == DbType.h2 || dbType == DbType.other;

        List<Placeholder> placeholders = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char current = sql.charAt(i);
            if (current == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i + 2);
                continue;
            }
            if (current == '#' && allowHashComment) {
                i = skipLineComment(sql, i + 1);
                continue;
            }
            if (current == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (current == '[' && allowBracketQuote) {
                i = skipBracketIdentifier(sql, i);
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                boolean backslashEscaped = usesBackslashEscape(sql, i, current, dbType);
                i = skipQuoted(sql, i, current, backslashEscaped, genericMode);
                continue;
            }
            if (current == '$' && allowDollarQuote) {
                String delimiter = dollarQuoteDelimiter(sql, i);
                if (delimiter != null) {
                    int contentStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, contentStart);
                    if (end < 0) {
                        throw new IllegalArgumentException(
                                "Unterminated PostgreSQL dollar-quoted string starting at index " + i);
                    }
                    i = end + delimiter.length();
                    continue;
                }
            }
            if (current == '?') {
                int end = i + 1;
                while (end < len && Character.isDigit(sql.charAt(end))) {
                    end++;
                }
                if (end > i + 1 && end < len
                        && (sql.charAt(end) == '#' || isIdentifierPart(sql.charAt(end)))) {
                    i = end;
                    continue;
                }
                int index = -1;
                if (end > i + 1) {
                    try {
                        index = Integer.parseInt(sql.substring(i + 1, end));
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid numbered SQL parameter at index " + i, ex);
                    }
                    if (index < 1) {
                        throw new IllegalArgumentException(
                                "Numbered SQL parameter index must be greater than zero: ?" + sql.substring(i + 1, end));
                    }
                }
                placeholders.add(new Placeholder(i, end, index));
                i = end;
                continue;
            }
            i++;
        }
        return placeholders;
    }

    private static boolean usesBackslashEscape(String sql, int quoteStart, char quote, @Nullable DbType dbType) {
        if (dbType == null) {
            return true;
        }
        if (dbType.isLikeMysql()) {
            return true;
        }
        if (dbType.isLikePostgres() && quote == '\'' && quoteStart > 0) {
            char prev = sql.charAt(quoteStart - 1);
            if ((prev == 'E' || prev == 'e')
                    && (quoteStart == 1 || !isIdentifierPart(sql.charAt(quoteStart - 2)))) {
                return true;
            }
        }
        return false;
    }

    private static int skipBracketIdentifier(String sql, int start) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            if (sql.charAt(i) == ']') {
                if (i + 1 < len && sql.charAt(i + 1) == ']') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int skipLineComment(String sql, int index) {
        int len = sql.length();
        while (index < len && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String sql, int start) {
        int len = sql.length();
        int depth = 1;
        int i = start + 2;
        while (i < len) {
            if (i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (i + 1 < len && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                i++;
            }
        }
        return len;
    }

    private static int skipQuoted(String sql, int start, char quote, boolean backslashEscaped, boolean genericMode) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            char current = sql.charAt(i);
            if (backslashEscaped && current == '\\' && i + 1 < len) {
                if (genericMode && i == start + 1 && sql.charAt(i + 1) == quote && isPrecededByEscapeKeyword(sql, start)) {
                    return i + 2;
                }
                i += 2;
                continue;
            }
            if (current == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static boolean isPrecededByEscapeKeyword(String sql, int quoteStart) {
        int k = quoteStart - 1;
        while (k >= 0 && Character.isWhitespace(sql.charAt(k))) {
            k--;
        }
        int wordEnd = k + 1;
        int wordStart = wordEnd - 6;
        if (wordStart < 0 || !sql.regionMatches(true, wordStart, "ESCAPE", 0, 6)) {
            return false;
        }
        return wordStart == 0 || !isIdentifierPart(sql.charAt(wordStart - 1));
    }

    private static String dollarQuoteDelimiter(String sql, int start) {
        if (start > 0) {
            char previous = sql.charAt(start - 1);
            if (previous == '$' || isIdentifierPart(previous)) {
                return null;
            }
        }
        int end = sql.indexOf('$', start + 1);
        if (end < 0) {
            return null;
        }
        if (end > start + 1) {
            char first = sql.charAt(start + 1);
            if (!Character.isLetter(first) && first != '_') {
                return null;
            }
            for (int i = start + 2; i < end; i++) {
                if (!isIdentifierPart(sql.charAt(i))) {
                    return null;
                }
            }
        }
        return sql.substring(start, end + 1);
    }

    private static boolean isIdentifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    public static String getJdbcStyleSql(String sql) {
        return getJdbcStyleSql(sql, null);
    }

    public static String getJdbcStyleSql(String sql, @Nullable DbType dbType) {
        List<Placeholder> placeholders = scanPlaceholders(sql, dbType);
        if (placeholders.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length());
        int copiedUntil = 0;
        for (Placeholder placeholder : placeholders) {
            sb.append(sql, copiedUntil, placeholder.start());
            sb.append('?');
            copiedUntil = placeholder.end();
        }
        sb.append(sql, copiedUntil, sql.length());
        return sb.toString();
    }

    /**
     * 统计 SQL 中有效占位符（{@code ?} 或 {@code ?1}）数量。
     */
    public static int countPlaceholders(String sql) {
        return countPlaceholders(sql, null);
    }

    public static int countPlaceholders(String sql, @Nullable DbType dbType) {
        return scanPlaceholders(sql, dbType).size();
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
