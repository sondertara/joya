package com.sondertara.joya.core.naming;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL 渲染与标识符命名策略。
 *
 * <p>遵循“显式注解优先”原则：
 * <ul>
 *   <li>当实体或属性未显式声明 {@code @Table(name)} / {@code @Column(name)}，或显式开启了全局大小写强制转换时，按策略转换。</li>
 *   <li>当属性通过 {@code @Column(name = "OrderNo")} 显式声明了混合大小写名称且未开启全局 {@code columnUppercase} 时，原样保留注解文本。</li>
 * </ul>
 *
 * <p>表名解析（动态分表 / schema 切换）通过 {@link TableNameResolver} 在渲染期完成，
 * 不再存在执行期的字符串改写。
 *
 * @param maxJoinTable      单次查询允许的最大关联表数量（默认 10）
 * @param columnUppercase   是否将列名转为大写
 * @param tableUppercase    是否将表名（含 schema）转为大写
 * @param tableNameResolver 物理表名解析策略
 */
public record NamingPolicy(
        int maxJoinTable,
        boolean columnUppercase,
        boolean tableUppercase,
        TableNameResolver tableNameResolver,
        char openQuote,
        char closeQuote
) {

    public static final int DEFAULT_MAX_JOIN_TABLE = 10;

    private static final Pattern SAFE_UNQUOTED_SEGMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    public static final NamingPolicy DEFAULT =
            new NamingPolicy(DEFAULT_MAX_JOIN_TABLE, false, false, TableNameResolver.IDENTITY, '\0', '\0');

    public NamingPolicy(
            int maxJoinTable,
            boolean columnUppercase,
            boolean tableUppercase,
            TableNameResolver tableNameResolver
    ) {
        this(maxJoinTable, columnUppercase, tableUppercase, tableNameResolver, '\0', '\0');
    }

    public NamingPolicy(int maxJoinTable, boolean columnUppercase, boolean tableUppercase) {
        this(maxJoinTable, columnUppercase, tableUppercase, TableNameResolver.IDENTITY, '\0', '\0');
    }

    public NamingPolicy {
        if (maxJoinTable <= 0) {
            maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        }
        tableNameResolver = Objects.requireNonNullElse(tableNameResolver, TableNameResolver.IDENTITY);
    }

    public NamingPolicy withQuotes(char openQuote, char closeQuote) {
        return new NamingPolicy(maxJoinTable, columnUppercase, tableUppercase, tableNameResolver, openQuote, closeQuote);
    }

    /** 派生新的动态表名解析策略，保留其余命名配置。 */
    public NamingPolicy withTableNameResolver(TableNameResolver resolver) {
        return new NamingPolicy(maxJoinTable, columnUppercase, tableUppercase,
                Objects.requireNonNullElse(resolver, TableNameResolver.IDENTITY), openQuote, closeQuote);
    }

    public @Nullable String formatTable(@Nullable String tableName) {
        if (tableName == null) {
            return null;
        }
        return tableUppercase ? tableName.toUpperCase(Locale.ROOT) : tableName;
    }

    public @Nullable String formatColumn(@Nullable String columnName) {
        if (columnName == null) {
            return null;
        }
        String cased = columnUppercase ? columnName.toUpperCase(Locale.ROOT) : columnName;
        return quoteIdentifier(cased);
    }

    /**
     * 解析最终写入 SQL 的表名：先按大小写策略格式化，再交给 {@link TableNameResolver}，最后按配置包裹标识符引号。
     *
     * @param schema 原始 schema 名，可能为 {@code null}
     * @param table  原始表名（不含 schema）
     */
    public String resolveTable(@Nullable String schema, String table) {
        Objects.requireNonNull(table, "table name must not be null");
        String formattedSchema = schema == null || schema.isBlank() ? null : formatTable(schema);
        String resolved = validateResolvedTable(tableNameResolver.resolve(formattedSchema, formatTable(table)));
        return quoteIdentifier(resolved);
    }

    private static String validateResolvedTable(String resolved) {
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("TableNameResolver must return a non-blank table name");
        }
        String trimmed = resolved.trim();
        if (trimmed.startsWith(".") || trimmed.endsWith(".") || trimmed.contains("..")) {
            throw new IllegalArgumentException(
                    "Invalid physical table name returned by TableNameResolver: '" + resolved + "'");
        }
        String[] segments = trimmed.split("\\.");
        for (String rawSegment : segments) {
            String segment = rawSegment.trim();
            if (!isSafeTableSegment(segment)) {
                throw new IllegalArgumentException(
                        "Invalid physical table name returned by TableNameResolver: '" + resolved
                                + "'. Only standard SQL identifiers (optionally schema-qualified) are allowed.");
            }
        }
        return trimmed;
    }

    private static boolean isSafeTableSegment(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        if (SAFE_UNQUOTED_SEGMENT.matcher(segment).matches()) {
            return true;
        }
        return isProperlyQuoted(segment, '"', '"')
                || isProperlyQuoted(segment, '`', '`')
                || isProperlyQuoted(segment, '[', ']');
    }

    public String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || openQuote == '\0' || closeQuote == '\0' || "*".equals(identifier.trim())) {
            return identifier;
        }
        String trimmed = identifier.trim();
        if (trimmed.indexOf('.') > 0) {
            String[] parts = trimmed.split("\\.");
            java.util.StringJoiner joiner = new java.util.StringJoiner(".");
            for (String part : parts) {
                joiner.add(quoteSingleSegment(part.trim()));
            }
            return joiner.toString();
        }
        return quoteSingleSegment(trimmed);
    }

    private String quoteSingleSegment(String segment) {
        if (segment.isEmpty() || "*".equals(segment)) {
            return segment;
        }
        if (isProperlyQuoted(segment, openQuote, closeQuote)) {
            return segment;
        }
        String closeStr = String.valueOf(closeQuote);
        String escaped = segment.replace(closeStr, closeStr + closeStr);
        return openQuote + escaped + closeQuote;
    }

    private static boolean isProperlyQuoted(String segment, char open, char close) {
        if (open == '\0' || close == '\0' || segment.length() < 2) {
            return false;
        }
        if (segment.charAt(0) != open || segment.charAt(segment.length() - 1) != close) {
            return false;
        }
        int end = segment.length() - 1;
        for (int i = 1; i < end; i++) {
            char c = segment.charAt(i);
            if (c == close) {
                if (i + 1 < end && segment.charAt(i + 1) == close) {
                    i++;
                } else {
                    return false;
                }
            } else if (open != close && c == open) {
                return false;
            }
        }
        return true;
    }
}
