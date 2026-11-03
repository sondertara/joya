package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
     * 将 SQL 片段中带表别名的驼峰列名转换为下划线风格（自动跳过引号字面量与注释）。
     */
    public static String underlineColumn(String sql) {
        if (Strings.isBlank(sql)) {
            return "";
        }
        return replaceTableAliasColumnsOutsideLiterals(sql, Strings::toUnderlineCase);
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
        boolean allowBracketQuote = genericMode || dbType.isLikeSqlServer();
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
            if (current == '#' && isHashCommentStart(sql, i, dbType)) {
                i = skipLineComment(sql, i + 1);
                continue;
            }
            if (current == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i, dbType);
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

    private static int skipBlockComment(String sql, int start, @Nullable DbType dbType) {
        int len = sql.length();
        boolean nested = dbType != null && dbType.isLikePostgres();
        int depth = 1;
        int i = start + 2;
        while (i < len) {
            if (nested && i + 1 < len && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
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
                if (i == start + 1 && sql.charAt(i + 1) == quote && isPrecededByEscapeKeyword(sql, start)) {
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
     * 剥离条件 SQL 中的表别名前缀（{@code t0.column} → {@code column}，自动跳过引号字面量与注释）。
     */
    public static String stripTableAlias(String sql) {
        if (Strings.isBlank(sql)) {
            return sql;
        }
        return replaceTableAliasColumnsOutsideLiterals(sql, group -> {
            int dot = group.indexOf('.');
            return dot >= 0 ? group.substring(dot + 1) : group;
        });
    }

    private static String replaceTableAliasColumnsOutsideLiterals(String sql, Function<String, String> replacer) {
        int len = sql.length();
        StringBuilder sb = new StringBuilder(len);
        int codeStart = 0;
        int i = 0;
        while (i < len) {
            char current = sql.charAt(i);
            int literalEnd = -1;
            if (current == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                literalEnd = skipLineComment(sql, i + 2);
            } else if (current == '#') {
                literalEnd = skipLineComment(sql, i + 1);
            } else if (current == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                literalEnd = skipBlockComment(sql, i, null);
            } else if (current == '\'' || current == '"' || current == '`') {
                boolean backslashEscaped = usesBackslashEscape(sql, i, current, null);
                literalEnd = skipQuoted(sql, i, current, backslashEscaped, true);
            } else if (current == '$') {
                String delimiter = dollarQuoteDelimiter(sql, i);
                if (delimiter != null) {
                    int contentStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, contentStart);
                    literalEnd = end >= 0 ? end + delimiter.length() : len;
                }
            }
            if (literalEnd > i) {
                if (i > codeStart) {
                    String segment = sql.substring(codeStart, i);
                    sb.append(COLUMN_WITH_TABLE_ALIAS.matcher(segment).replaceAll(m -> replacer.apply(m.group())));
                }
                sb.append(sql, i, literalEnd);
                i = literalEnd;
                codeStart = i;
            } else {
                i++;
            }
        }
        if (codeStart < len) {
            String segment = sql.substring(codeStart, len);
            sb.append(COLUMN_WITH_TABLE_ALIAS.matcher(segment).replaceAll(m -> replacer.apply(m.group())));
        }
        return sb.toString();
    }

    /**
     * 判断 {@code index} 处的 {@code '#'} 是否表示行注释起点：
     * <ul>
     *   <li>当 {@code dbType != null} 时，仅 MySQL 家族（{@link DbType#isLikeMysql()}）将 {@code #} 视为行注释；
     *       SQL Server（{@code #temp} 临时表）、PostgreSQL（{@code #} 异或/JSON 运算符）、Oracle/H2 等均不视为注释。</li>
     *   <li>当 {@code dbType == null}（通用模式）时，仅当 {@code #} 位于行首（该行前导仅空白）、后方非运算符组合
     *       （排除 {@code ##} / {@code #>} / {@code #-} / {@code #=}）且前导词元不是表引用引导词
     *       （排除跨行 {@code FROM\n#temp}）时视为行注释。</li>
     * </ul>
     */
    private static boolean isHashCommentStart(String sql, int index, @Nullable DbType dbType) {
        if (dbType != null) {
            return dbType.isLikeMysql();
        }
        boolean nextIsIdentifier = false;
        if (index + 1 < sql.length()) {
            char next = sql.charAt(index + 1);
            if (next == '#' || next == '>' || next == '-' || next == '=') {
                return false;
            }
            nextIsIdentifier = isIdentifierPart(next);
        }
        int k = index - 1;
        while (k >= 0) {
            char prev = sql.charAt(k);
            if (prev == '\n' || prev == '\r') {
                return !nextIsIdentifier || !isPrecededByTableKeyword(sql, k);
            }
            if (!Character.isWhitespace(prev)) {
                return false;
            }
            k--;
        }
        return true;
    }

    private static boolean isPrecededByTableKeyword(String sql, int beforeIndex) {
        int i = beforeIndex;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        if (sql.charAt(i) == ',') {
            return true;
        }
        int end = i + 1;
        while (i >= 0 && isIdentifierPart(sql.charAt(i))) {
            i--;
        }
        String word = sql.substring(i + 1, end).toUpperCase(java.util.Locale.ROOT);
        return switch (word) {
            case "FROM", "JOIN", "INTO", "UPDATE", "TABLE" -> true;
            default -> false;
        };
    }

    /**
     * 跳过从 {@code start} 起的前导空白与注释，返回首个非空白/注释字符下标。
     */
    public static int skipTrivia(String sql, int start) {
        return skipTrivia(sql, start, null);
    }

    /**
     * 跳过从 {@code start} 起的前导空白与方言注释，返回首个非空白/注释字符下标。
     */
    public static int skipTrivia(String sql, int start, @Nullable DbType dbType) {
        if (sql == null) {
            return 0;
        }
        int len = sql.length();
        int i = Math.max(0, start);
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i + 2);
            } else if (c == '#' && (dbType == null || dbType.isLikeMysql())) {
                i = skipLineComment(sql, i + 1);
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i, dbType);
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * 在顶层（括号深度为 0，且跳过单/双/反引号、方括号标识符、美元引号、反斜杠转义及各类注释）
     * 查找指定 token 出现的所有起始下标。
     */
    public static List<Integer> topLevelTokens(String sql, String token, boolean keyword) {
        return topLevelTokens(sql, token, keyword, null);
    }

    /**
     * 在顶层（按指定方言词法规则跳过字面量、标识符与注释）查找指定 token 出现的所有起始下标。
     */
    public static List<Integer> topLevelTokens(String sql, String token, boolean keyword, @Nullable DbType dbType) {
        List<Integer> result = new ArrayList<>();
        if (sql == null || token == null || token.isEmpty()) {
            return result;
        }
        boolean genericMode = (dbType == null);
        boolean allowBracketQuote = genericMode || dbType.isLikeSqlServer();
        boolean allowDollarQuote = genericMode || dbType.isLikePostgres() || dbType == DbType.h2 || dbType == DbType.other;
        int len = sql.length();
        int depth = 0;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i + 2);
                continue;
            }
            if (c == '#' && isHashCommentStart(sql, i, dbType)) {
                i = skipLineComment(sql, i + 1);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i, dbType);
                continue;
            }
            if (c == '[' && allowBracketQuote) {
                i = skipBracketIdentifier(sql, i);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                boolean backslashEscaped = usesBackslashEscape(sql, i, c, dbType);
                i = skipQuoted(sql, i, c, backslashEscaped, genericMode);
                continue;
            }
            if (c == '$' && allowDollarQuote) {
                String delimiter = dollarQuoteDelimiter(sql, i);
                if (delimiter != null) {
                    int contentStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, contentStart);
                    i = end >= 0 ? end + delimiter.length() : len;
                    continue;
                }
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth == 0 && matchesToken(sql, i, token, keyword)) {
                result.add(i);
            }
            i++;
        }
        return result;
    }

    /**
     * 判断 SQL 是否在未换行的行注释中结束（跳过引号、美元引号与块注释）。
     */
    public static boolean endsInLineComment(String sql) {
        return endsInLineComment(sql, null);
    }

    /**
     * 按指定方言判断 SQL 是否在未换行的行注释中结束。
     */
    public static boolean endsInLineComment(String sql, @Nullable DbType dbType) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        boolean genericMode = (dbType == null);
        boolean allowBracketQuote = genericMode || dbType.isLikeSqlServer();
        boolean allowDollarQuote = genericMode || dbType.isLikePostgres() || dbType == DbType.h2 || dbType == DbType.other;
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = skipLineComment(sql, i + 2);
                if (end >= len) {
                    return true;
                }
                i = end;
                continue;
            }
            if (c == '#' && isHashCommentStart(sql, i, dbType)) {
                int end = skipLineComment(sql, i + 1);
                if (end >= len) {
                    return true;
                }
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i, dbType);
                continue;
            }
            if (c == '[' && allowBracketQuote) {
                i = skipBracketIdentifier(sql, i);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                boolean backslashEscaped = usesBackslashEscape(sql, i, c, dbType);
                i = skipQuoted(sql, i, c, backslashEscaped, genericMode);
                continue;
            }
            if (c == '$' && allowDollarQuote) {
                String delimiter = dollarQuoteDelimiter(sql, i);
                if (delimiter != null) {
                    int contentStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, contentStart);
                    i = end >= 0 ? end + delimiter.length() : len;
                    continue;
                }
            }
            i++;
        }
        return false;
    }

    /**
     * 判断 SQL 顶层是否已包含分页子句（{@code LIMIT} / {@code OFFSET} / {@code FETCH FIRST|NEXT}），
     * 自动忽略注释、引号字面量、美元引号及括号内子查询中的同名关键字。
     */
    public static boolean hasTopLevelPagination(String sql) {
        return hasTopLevelPagination(sql, null);
    }

    /**
     * 按指定方言判断 SQL 顶层是否已包含分页子句。
     */
    public static boolean hasTopLevelPagination(String sql, @Nullable DbType dbType) {
        if (Strings.isBlank(sql)) {
            return false;
        }
        return !topLevelTokens(sql, "LIMIT", true, dbType).isEmpty()
                || !topLevelTokens(sql, "OFFSET", true, dbType).isEmpty()
                || !topLevelTokens(sql, "FETCH", true, dbType).isEmpty();
    }

    /**
     * 从原生 DML/DDL SQL 首关键字推断 {@link Operation}（自动跳过前导空白与注释）。
     */
    public static Operation inferDmlOperation(String sql) {
        return inferDmlOperation(sql, null);
    }

    /**
     * 按指定方言从原生 DML/DDL SQL 首关键字推断 {@link Operation}。
     */
    public static Operation inferDmlOperation(String sql, @Nullable DbType dbType) {
        if (Strings.isBlank(sql)) {
            return Operation.UPDATE;
        }
        int start = skipTrivia(sql, 0, dbType);
        int end = start;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return Operation.UPDATE;
        }
        String keyword = sql.substring(start, end).toUpperCase(java.util.Locale.ROOT);
        if ("WITH".equals(keyword)) {
            int earliestPos = Integer.MAX_VALUE;
            Operation inferred = Operation.UPDATE;
            for (String candidate : List.of("INSERT", "REPLACE", "MERGE", "UPSERT",
                    "DELETE", "TRUNCATE", "UPDATE", "SELECT")) {
                List<Integer> positions = topLevelTokens(sql, candidate, true, dbType);
                if (!positions.isEmpty() && positions.get(0) < earliestPos) {
                    earliestPos = positions.get(0);
                    inferred = mapKeywordToOperation(candidate);
                }
            }
            return inferred;
        }
        return mapKeywordToOperation(keyword);
    }

    private static Operation mapKeywordToOperation(String keyword) {
        return switch (keyword) {
            case "INSERT", "REPLACE", "MERGE", "UPSERT" -> Operation.INSERT;
            case "DELETE", "TRUNCATE" -> Operation.DELETE;
            case "SELECT" -> Operation.SELECT;
            default -> Operation.UPDATE;
        };
    }

    private static boolean matchesToken(String sql, int index, String token, boolean keyword) {
        if (index < 0 || index + token.length() > sql.length()
                || !sql.regionMatches(true, index, token, 0, token.length())) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return (index == 0 || !isSqlWordPart(sql.charAt(index - 1)))
                && (index + token.length() == sql.length()
                || !isSqlWordPart(sql.charAt(index + token.length())));
    }

    private static boolean isSqlWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
