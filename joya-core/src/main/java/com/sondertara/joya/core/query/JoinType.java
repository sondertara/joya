package com.sondertara.joya.core.query;

import lombok.Getter;

/**
 * 关联类型。
 */
@Getter
public enum JoinType {
    /**
     * Join type of two table
     */
    JOIN("JOIN"),
    INNER_JOIN("JOIN"),
    LEFT_JOIN("LEFT JOIN"),
    RIGHT_JOIN("RIGHT JOIN");

    private final String code;

    JoinType(String code) {
        this.code = code;
    }
}
