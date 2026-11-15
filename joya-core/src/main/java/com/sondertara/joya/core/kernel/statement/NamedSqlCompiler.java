package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

/**
 * 命名参数 SQL 编译器：将 {@code :paramName} 编译为标准 JDBC {@code ?} 占位符及有序 {@link SqlParameter} 列表。
 *
 * <p>复用 {@link SqlFormatUtils} 的方言感知词法辅助能力：
 * <ul>
 *   <li>跳过单行注释（{@code --} / MySQL {@code #}）、块注释（含 PostgreSQL 嵌套块注释）、
 *       单/双/反引号字面量或标识符、SQL Server {@code [...]} 与 PostgreSQL {@code $tag$...$tag$}；</li>
 *   <li>原样保留 PostgreSQL {@code ::type} 类型转换与 {@code :=} 赋值运算符；</li>
 *   <li>支持 {@link Collection} 展开为 {@code ?, ?, ...}（拒绝空集合与超过 {@value #MAX_COLLECTION_SIZE} 个元素）；</li>
 *   <li>严禁与位置占位符 {@code ?} 混用，参数缺失时快速失败（允许显式绑定 {@code null} 值）。</li>
 * </ul>
 */
public final class NamedSqlCompiler {

    public static final int MAX_COLLECTION_SIZE = 1000;

    private NamedSqlCompiler() {
    }

    /**
     * 编译含命名参数的 SQL 为标准 {@link SqlStatement}。
     */
    public static SqlStatement compile(String sql,
                                       @Nullable Map<String, ?> params,
                                       @Nullable Operation operation,
                                       @Nullable DbType dbType) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be null or blank");
        }
        Map<String, ?> effectiveParams = params == null ? Map.of() : params;
        Operation effectiveOp = operation == null ? Operation.SELECT : operation;

        boolean genericMode = (dbType == null);
        boolean allowBracketQuote = genericMode || dbType.isLikeSqlServer();
        boolean allowDollarQuote = genericMode || dbType.isLikePostgres() || dbType == DbType.h2 || dbType == DbType.other;

        int len = sql.length();
        StringBuilder out = new StringBuilder(len);
        SqlParameterList paramList = new SqlParameterList();
        int i = 0;

        while (i < len) {
            char current = sql.charAt(i);
            if (current == '-' && SqlFormatUtils.isDashCommentStart(sql, i, dbType)) {
                int end = SqlFormatUtils.skipLineComment(sql, i + 2);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (current == '#' && SqlFormatUtils.isHashCommentStart(sql, i, dbType)) {
                int end = SqlFormatUtils.skipLineComment(sql, i + 1);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (current == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = SqlFormatUtils.findBlockCommentEnd(sql, i, dbType);
                int stop = end < 0 ? len : end;
                out.append(sql, i, stop);
                i = stop;
                continue;
            }
            if (current == '[' && allowBracketQuote) {
                int end = SqlFormatUtils.findBracketIdentifierEnd(sql, i);
                int stop = end < 0 ? len : end;
                out.append(sql, i, stop);
                i = stop;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                boolean backslashEscaped = SqlFormatUtils.usesBackslashEscape(sql, i, current, dbType);
                int end = SqlFormatUtils.findQuotedEnd(sql, i, current, backslashEscaped);
                int stop = end < 0 ? len : end;
                out.append(sql, i, stop);
                i = stop;
                continue;
            }
            if (current == '$' && allowDollarQuote) {
                String delimiter = SqlFormatUtils.dollarQuoteDelimiter(sql, i);
                if (delimiter != null) {
                    int contentStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, contentStart);
                    if (end < 0) {
                        throw new IllegalArgumentException(
                                "Unterminated PostgreSQL dollar-quoted string starting at index " + i);
                    }
                    int stop = end + delimiter.length();
                    out.append(sql, i, stop);
                    i = stop;
                    continue;
                }
            }
            if (current == '?') {
                throw new IllegalArgumentException(
                        "Cannot mix positional '?' placeholders with named ':param' parameters in SQL: " + sql);
            }
            if (current == ':') {
                if (i + 1 < len) {
                    char next = sql.charAt(i + 1);
                    if (next == ':' || next == '=') {
                        out.append(current).append(next);
                        i += 2;
                        continue;
                    }
                    boolean precededByIdent = i > 0 && SqlFormatUtils.isIdentifierPart(sql.charAt(i - 1));
                    if (!precededByIdent && isNameStart(next)) {
                        int end = i + 2;
                        while (end < len && SqlFormatUtils.isIdentifierPart(sql.charAt(end))) {
                            end++;
                        }
                        String name = sql.substring(i + 1, end);
                        appendNamedParameter(name, effectiveParams, out, paramList);
                        i = end;
                        continue;
                    }
                }
            }
            out.append(current);
            i++;
        }

        return SqlStatement.of(
                out.toString(),
                paramList.toList(),
                StatementMetadata.builder(effectiveOp).dbType(dbType).build());
    }

    private static boolean isNameStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static void appendNamedParameter(String name,
                                             Map<String, ?> params,
                                             StringBuilder out,
                                             SqlParameterList paramList) {
        if (!params.containsKey(name)) {
            throw new IllegalArgumentException("Missing named SQL parameter: :" + name);
        }
        Object value = params.get(name);
        if (value instanceof Collection<?> col) {
            if (col.isEmpty()) {
                throw new IllegalArgumentException("Named SQL collection parameter :" + name + " must not be empty");
            }
            if (col.size() > MAX_COLLECTION_SIZE) {
                throw new IllegalArgumentException(
                        "Named SQL collection parameter :" + name + " exceeds max expansion size "
                                + MAX_COLLECTION_SIZE + ": " + col.size());
            }
            int idx = 0;
            for (Object elem : col) {
                if (idx > 0) {
                    out.append(", ");
                }
                out.append(paramList.add(name + "[" + idx + "]", elem, ParameterSource.RAW));
                idx++;
            }
            return;
        }
        out.append(paramList.add(name, value, ParameterSource.RAW));
    }
}
