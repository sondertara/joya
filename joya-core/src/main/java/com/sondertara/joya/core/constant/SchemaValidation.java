package com.sondertara.joya.core.constant;

/**
 * schema 校验策略
 */
public enum SchemaValidation {
    /**
     * 不执行 schema 校验（默认）
     */
    NONE,
    /**
     * 校验不一致时仅日志 warning
     */
    WARN,
    /**
     * 校验不一致时抛出 DbException
     */
    THROW
}
