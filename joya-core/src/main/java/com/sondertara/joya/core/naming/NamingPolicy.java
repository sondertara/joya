package com.sondertara.joya.core.naming;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

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

    public String formatTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        return tableUppercase ? tableName.toUpperCase(Locale.ROOT) : tableName;
    }

    public String formatColumn(String columnName) {
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
        String resolved = tableNameResolver.resolve(formattedSchema, formatTable(table));
        return quoteIdentifier(resolved);
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
        if (segment.length() >= 2 && segment.charAt(0) == openQuote && segment.charAt(segment.length() - 1) == closeQuote) {
            return segment;
        }
        String closeStr = String.valueOf(closeQuote);
        String escaped = segment.replace(closeStr, closeStr + closeStr);
        return openQuote + escaped + closeQuote;
    }
}
