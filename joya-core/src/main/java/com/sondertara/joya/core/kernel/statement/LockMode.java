package com.sondertara.joya.core.kernel.statement;

/**
 * 查询悲观行锁模式（{@code SELECT ... FOR UPDATE} 变体）。
 */
public enum LockMode {

    /**
     * 不加锁（默认）。
     */
    NONE,

    /**
     * 标准排他行锁：{@code FOR UPDATE}。
     */
    FOR_UPDATE,

    /**
     * 无法立即获取锁时立即报错，不等待：{@code FOR UPDATE NOWAIT}。
     */
    FOR_UPDATE_NOWAIT,

    /**
     * 跳过已被其他事务锁定的行：{@code FOR UPDATE SKIP LOCKED}。
     */
    FOR_UPDATE_SKIP_LOCKED
}
