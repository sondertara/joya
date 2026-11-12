package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方言感知的 SQL 脚本词法切分器（纯函数，与 {@link SqlFormatUtils} 共享词法扫描规则）。
 *
 * <p>仅在顶层 {@code normal} 词法状态匹配语句分隔符（默认 {@code ;}，支持行首 {@code @DELIMITER} 指令切换），
 * 自动跳过单/双/反引号字面量与标识符（含 MySQL 反斜杠转义、PostgreSQL {@code E'...'}）、SQL Server {@code [...]} 标识符、
 * PostgreSQL {@code $tag$...$tag$} 美元引号、{@code --}/{@code #}/{@code //} 行注释以及 {@code /* ... * /} 块注释（含 PG 嵌套）。
 *
 * <p><b>分隔符匹配边界</b>：分隔符按字面量子串匹配且区分大小写（与历史 {@code ScriptRunner} 的行为一致）。
 * 使用 {@code @DELIMITER GO} 这类单词分隔符时，须与脚本体中的大小写保持一致，并注意标识符中若含该子串会被提前切分；
 * 多字符符号分隔符（如 {@code $$}）不受此限。
 *
 * <p><b>前置快速失败（Fail-Fast）与防泄密契约</b>：整份脚本在执行任何语句前一次性切分完毕；
 * 遇到未闭合字面量、未闭合块注释或尾部残缺未闭合分隔符的语句时，立即抛出仅包含词法类别与 1 基起始行号的异常（不回显原始 SQL 明文）。
 */
public final class SqlScriptSplitter {

    private static final String DEFAULT_DELIMITER = ";";

    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile(
            "^\\s*(?:(?:--)|(?://))?\\s*(?://)?\\s*@DELIMITER\\s+(\\S+)(?:\\s+(?:--|//).*)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 切分出的一条待执行语句及其在原脚本中的 1 基起始行号。
     */
    public record Statement(String sql, int line) {
        public Statement {
            sql = Objects.requireNonNull(sql, "sql must not be null");
            if (line < 1) {
                throw new IllegalArgumentException("line must be >= 1: " + line);
            }
        }
    }

    private SqlScriptSplitter() {
        throw new IllegalStateException("Utility class cannot be instantiated");
    }

    /**
     * 使用通用词法规则一次性切分 SQL 脚本。
     */
    public static List<Statement> split(String script) {
        return split(script, null);
    }

    /**
     * 按指定方言词法规则一次性切分 SQL 脚本；词法错误在返回前即抛出带 1 基行号的异常。
     */
    public static List<Statement> split(String script, @Nullable DbType dbType) {
        if (script == null || script.isEmpty()) {
            return List.of();
        }
        String sql = script.charAt(0) == '\uFEFF' ? script.substring(1) : script;
        if (sql.isEmpty()) {
            return List.of();
        }

        boolean genericMode = (dbType == null);
        boolean allowBracketQuote = genericMode || dbType.isLikeSqlServer();
        boolean allowDollarQuoteBase = genericMode || dbType.isLikePostgres()
                || dbType == DbType.h2 || dbType == DbType.other;

        List<Statement> statements = new ArrayList<>();
        StringBuilder currentSql = new StringBuilder();
        String delimiter = DEFAULT_DELIMITER;
        boolean hasNonTriviaToken = false;
        boolean atLineStart = true;
        int stmtStartLine = 1;
        int line = 1;
        int len = sql.length();
        int i = 0;

        while (i < len) {
            // 1. 语句间行首 @DELIMITER 指令切换分隔符
            if (atLineStart && !hasNonTriviaToken) {
                int lineEnd = SqlFormatUtils.skipLineComment(sql, i);
                Matcher matcher = DELIMITER_DIRECTIVE.matcher(sql.substring(i, lineEnd));
                if (matcher.matches()) {
                    delimiter = matcher.group(1);
                    i = lineEnd;
                    atLineStart = false;
                    continue;
                }
            }

            char c = sql.charAt(i);

            // 2. 顶层 normal 状态优先匹配当前有效分隔符
            if (sql.startsWith(delimiter, i)) {
                if (hasNonTriviaToken) {
                    String trimmed = currentSql.toString().trim();
                    if (!trimmed.isEmpty()) {
                        statements.add(new Statement(trimmed, stmtStartLine));
                    }
                    currentSql.setLength(0);
                    hasNonTriviaToken = false;
                }
                int delimEnd = i + delimiter.length();
                line += countNewlines(sql, i, delimEnd);
                i = delimEnd;
                atLineStart = false;
                continue;
            }

            // 3. 换行与空白处理
            if (c == '\r' || c == '\n') {
                int next = (c == '\r' && i + 1 < len && sql.charAt(i + 1) == '\n') ? i + 2 : i + 1;
                if (hasNonTriviaToken) {
                    currentSql.append(sql, i, next);
                }
                line++;
                i = next;
                atLineStart = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (hasNonTriviaToken) {
                    currentSql.append(c);
                }
                i++;
                continue;
            }

            // 4. 各类行注释与块注释（注释内的分号不切分）
            if (c == '-' && SqlFormatUtils.isDashCommentStart(sql, i, dbType)) {
                int end = SqlFormatUtils.skipLineComment(sql, i + 2);
                if (hasNonTriviaToken) {
                    currentSql.append(sql, i, end);
                }
                i = end;
                atLineStart = false;
                continue;
            }
            if (c == '#' && SqlFormatUtils.isHashCommentStart(sql, i, dbType)) {
                int end = SqlFormatUtils.skipLineComment(sql, i + 1);
                if (hasNonTriviaToken) {
                    currentSql.append(sql, i, end);
                }
                i = end;
                atLineStart = false;
                continue;
            }
            if (atLineStart && c == '/' && i + 1 < len && sql.charAt(i + 1) == '/') {
                // 兼容历史 ScriptRunner 的行首 // 注释指令（不拼入送往 JDBC 的 SQL 文本）
                i = SqlFormatUtils.skipLineComment(sql, i + 2);
                atLineStart = false;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int commentStartLine = line;
                int end = SqlFormatUtils.findBlockCommentEnd(sql, i, dbType);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "Unterminated block comment starting at line " + commentStartLine);
                }
                if (hasNonTriviaToken) {
                    currentSql.append(sql, i, end);
                }
                line += countNewlines(sql, i, end);
                i = end;
                atLineStart = false;
                continue;
            }

            // 5. 字面量与引号标识符（从此开始均视为有效语句 token）
            if (!hasNonTriviaToken) {
                hasNonTriviaToken = true;
                stmtStartLine = line;
            }
            atLineStart = false;

            if (c == '[' && allowBracketQuote) {
                int tokenStartLine = line;
                int end = SqlFormatUtils.findBracketIdentifierEnd(sql, i);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "Unterminated bracket identifier starting at line " + tokenStartLine);
                }
                currentSql.append(sql, i, end);
                line += countNewlines(sql, i, end);
                i = end;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int tokenStartLine = line;
                boolean backslashEscaped = SqlFormatUtils.usesBackslashEscape(sql, i, c, dbType);
                int end = SqlFormatUtils.findQuotedEnd(sql, i, c, backslashEscaped);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "Unterminated quoted literal (" + describeQuote(c) + ") starting at line " + tokenStartLine);
                }
                currentSql.append(sql, i, end);
                line += countNewlines(sql, i, end);
                i = end;
                continue;
            }
            if (c == '$' && allowDollarQuoteBase && !delimiter.startsWith("$")) {
                String tag = SqlFormatUtils.dollarQuoteDelimiter(sql, i);
                if (tag != null) {
                    int tokenStartLine = line;
                    int contentStart = i + tag.length();
                    int closeIdx = sql.indexOf(tag, contentStart);
                    if (closeIdx < 0) {
                        throw new IllegalArgumentException(
                                "Unterminated dollar-quoted literal starting at line " + tokenStartLine);
                    }
                    int end = closeIdx + tag.length();
                    currentSql.append(sql, i, end);
                    line += countNewlines(sql, i, end);
                    i = end;
                    continue;
                }
            }

            currentSql.append(c);
            i++;
        }

        if (hasNonTriviaToken) {
            throw new IllegalArgumentException(
                    "SQL statement starting at line " + stmtStartLine
                            + " is missing end-of-statement terminator (" + delimiter + ")");
        }
        return List.copyOf(statements);
    }

    private static String describeQuote(char quote) {
        return switch (quote) {
            case '\'' -> "single quote";
            case '"' -> "double quote";
            case '`' -> "backtick";
            default -> "quote";
        };
    }

    private static int countNewlines(String sql, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) {
            char ch = sql.charAt(k);
            if (ch == '\r') {
                count++;
                if (k + 1 < to && sql.charAt(k + 1) == '\n') {
                    k++;
                }
            } else if (ch == '\n') {
                count++;
            }
        }
        return count;
    }
}
