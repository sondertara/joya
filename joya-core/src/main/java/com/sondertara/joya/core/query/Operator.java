package com.sondertara.joya.core.query;

import lombok.Getter;

@Getter
public enum Operator {
    /**
     * 相等
     */
    EQ("=", 1),
    /**
     * like
     */
    LIKE("LIKE", 1),
    /**
     * 左like %kk
     */
    LIKE_L("LIKE", 1),
    /**
     * not like '%kk'
     */
    NOT_LIKE_L("NOT LIKE", 1),
    /**
     * 右like kk%
     */
    LIKE_R("LIKE", 1),
    /**
     * not like 'kk%'
     */
    NOT_LIKE_R("NOT LIKE", 1),
    NOT_LIKE("NOT LIKE", 1),
    /**
     * 大于
     */
    GT(">", 1),
    /**
     * 小于
     */
    LT("<", 1),
    /**
     * 大于等于
     */
    GTE(">=", 1),
    /**
     * 小于等于
     */
    LTE("<=", 1),
    /**
     * IN范围查询
     */
    IN("IN", -1),
    /**
     * 不等
     */
    NEQ("!=", 1),
    IS_NULL("IS NULL", 0),
    IS_NOT_NULL("IS NOT NULL", 0),
    IS_EMPTY("=", 0),
    IS_NOT_EMPTY("!=", 0),

    NOT_IN("NOT IN", -1),
    BETWEEN("BETWEEN", 2),
    NOT_BETWEEN("NOT BETWEEN", 2),
    AND("AND", -1),
    NONE("",0),
    OR("OR", -1);

    private final String connector;
    private final int paramCount;

    Operator(String connector, int paramCount) {
        this.connector = connector;
        this.paramCount = paramCount;
    }

    public String getConnector() {
        return " " + connector + " ";
    }
}