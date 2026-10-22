package com.sondertara.joya.core.kernel.dialect;

import java.util.StringJoiner;

/**
 * 数据库标识符（表名、列名、Schema 名）引用转义风格。
 */
public enum QuoteStyle {

    /** 反引号风格（MySQL / MariaDB / TiDB / ClickHouse / GBase）。 */
    BACKTICK('`', '`'),

    /** ANSI 双引号风格（PostgreSQL / Oracle / H2 / SQLite / DB2 / 达梦 / 金仓 / GaussDB）。 */
    DOUBLE_QUOTE('"', '"'),

    /** 方括号风格（SQL Server / Sybase）。 */
    BRACKET('[', ']'),

    /** 不转义。 */
    NONE('\0', '\0');

    private final char openQuote;
    private final char closeQuote;

    QuoteStyle(char openQuote, char closeQuote) {
        this.openQuote = openQuote;
        this.closeQuote = closeQuote;
    }

    public char openQuote() {
        return openQuote;
    }

    public char closeQuote() {
        return closeQuote;
    }

    /**
     * 对标识符（支持 {@code schema.table} 点号分隔形式）进行安全转义。
     */
    public String quote(String identifier) {
        if (identifier == null || identifier.isBlank() || this == NONE || "*".equals(identifier.trim())) {
            return identifier;
        }
        String trimmed = identifier.trim();
        if (trimmed.indexOf('.') > 0) {
            String[] parts = trimmed.split("\\.");
            StringJoiner joiner = new StringJoiner(".");
            for (String part : parts) {
                joiner.add(quoteSingle(part.trim()));
            }
            return joiner.toString();
        }
        return quoteSingle(trimmed);
    }

    private String quoteSingle(String part) {
        if (part.isEmpty() || "*".equals(part)) {
            return part;
        }
        if (part.length() >= 2 && part.charAt(0) == openQuote && part.charAt(part.length() - 1) == closeQuote) {
            return part;
        }
        String escaped = part.replace(String.valueOf(closeQuote), String.valueOf(closeQuote) + closeQuote);
        return openQuote + escaped + closeQuote;
    }
}
