package com.sondertara.joya.core.kernel.statement;

/**
 * SQL 操作类型，用于改写器/监听器路由与读写分离判断。
 */
public enum Operation {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    TRUNCATE,
    DDL
}
