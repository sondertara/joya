package com.sondertara.joya.core.query.pagination;

/**
 * 过滤操作符枚举 —— 严格限定允许的动态查询操作类型。
 *
 * <p>同时支持按 camelCase（如 {@code likeLeft} / {@code isNull}）或枚举名（如 {@code LIKE_LEFT} / {@code IS_NULL}）解析。
 */
public enum FilterOperator {

    EQ("eq", "="),
    NE("ne", "<>"),
    GT("gt", ">"),
    GE("ge", ">="),
    LT("lt", "<"),
    LE("le", "<="),
    LIKE("like", "LIKE"),               // %value%
    LIKE_LEFT("likeLeft", "LIKE"),      // %value
    LIKE_RIGHT("likeRight", "LIKE"),    // value%
    IN("in", "IN"),
    NOT_IN("notIn", "NOT IN"),
    BETWEEN("between", "BETWEEN"),
    IS_NULL("isNull", "IS NULL"),
    IS_NOT_NULL("isNotNull", "IS NOT NULL");

    private final String code;
    private final String sqlSymbol;

    FilterOperator(String code, String sqlSymbol) {
        this.code = code;
        this.sqlSymbol = sqlSymbol;
    }

    public String getCode() {
        return code;
    }

    public String getSqlSymbol() {
        return sqlSymbol;
    }

    /**
     * 按 camelCase {@code code} 或大写下划线枚举名（忽略大小写）解析操作符。
     */
    public static FilterOperator fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("FilterOperator code must not be null or blank");
        }
        String trimmed = code.trim();
        for (FilterOperator op : values()) {
            if (op.code.equalsIgnoreCase(trimmed) || op.name().equalsIgnoreCase(trimmed)) {
                return op;
            }
        }
        throw new IllegalArgumentException("Unsupported FilterOperator: " + code);
    }
}
