package com.sondertara.joya.core.query.pagination;

/**
 * 排序方向枚举。
 */
public enum SortDirection {

    ASC,
    DESC;

    /**
     * 按忽略大小写的字符串解析排序方向（为空时默认返回 {@link #ASC}）。
     */
    public static SortDirection fromCode(String code) {
        if (code == null || code.isBlank()) {
            return ASC;
        }
        String trimmed = code.trim();
        for (SortDirection dir : values()) {
            if (dir.name().equalsIgnoreCase(trimmed)) {
                return dir;
            }
        }
        throw new IllegalArgumentException("Unsupported sort direction: " + code);
    }
}
