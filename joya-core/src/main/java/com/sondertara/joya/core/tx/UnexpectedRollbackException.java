package com.sondertara.joya.core.tx;

/**
 * 请求提交，但事务已被标记为仅回滚（不变量 I7）。
 *
 * <p>典型场景：内层事务调用了 rollback，外层仍尝试 commit。
 * 共享同一物理事务时，内层回滚必然污染全局——此时必须<b>响亮失败</b>，
 * 而不是静默提交（旧实现的致命缺陷 P0-1 正是静默提交了外层）。
 */
public class UnexpectedRollbackException extends TxException {

    public UnexpectedRollbackException(String message) {
        super(message);
    }

    public UnexpectedRollbackException(String message, Throwable cause) {
        super(message, cause);
    }
}
