package com.sondertara.joya.core.kernel.statement;

/**
 * SQL 操作类型，用于拦截器路由与读写分离判断。
 */
public enum Operation {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    BATCH,
    SCRIPT
}
