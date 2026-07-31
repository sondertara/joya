package com.sondertara.joya.core.jdbc.transaction;

/**
 * 事务传播行为。
 *
 * <p>语义对齐 Spring 的同名字段，但实现是 Joya 自有的本地事务栈。</p>
 *
 * @author huangxiaohu
 */
public enum Propagation {
    /**
     * 加入已有事务；不存在则开启新事务。
     */
    REQUIRED,
    /**
     * 总是开启新事务；若当前存在事务，则将其挂起。
     */
    REQUIRES_NEW,
    /**
     * 嵌套事务：在当前事务中通过 Savepoint 开启子事务；不存在外层事务则开启新事务。
     */
    NESTED,
    /**
     * 跟随环境：有事务则加入，没有则以非事务方式执行。
     */
    SUPPORTS,
    /**
     * 非事务方式执行；若当前存在事务，则将其挂起。
     */
    NOT_SUPPORTED,
    /**
     * 排除事务：当前存在事务则抛异常。
     */
    NEVER,
    /**
     * 强制要求事务：当前不存在事务则抛异常。
     */
    MANDATORY,
}
