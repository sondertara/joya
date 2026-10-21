package com.sondertara.joya.core.kernel.statement;

/**
 * 参数来源，用于调试、审计与拦截器改写时区分参数语义。
 * <p>
 * 例如租户拦截器追加的参数标记为 {@link #TENANT}，便于审计日志与分片路由识别。
 */
public enum ParameterSource {
    /** INSERT 列值 */
    INSERT,
    /** UPDATE SET 子句 */
    SET,
    /** WHERE 条件 */
    WHERE,
    /** JOIN 条件 */
    JOIN,
    /** 租户隔离字段 */
    TENANT,
    /** 审计字段（创建人/更新人/时间戳） */
    AUDIT,
    /** 分页偏移/限制 */
    PAGINATION,
    /** 原生 SQL 或未知来源 */
    RAW
}
