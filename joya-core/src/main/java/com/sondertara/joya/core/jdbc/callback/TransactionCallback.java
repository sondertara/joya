
package com.sondertara.joya.core.jdbc.callback;

import com.sondertara.joya.core.jdbc.transaction.TransactionStatus;

/**
 * 事务模版
 * @version : 2015年8月11日
 * @author 赵永春 (zyc@hasor.net)
 */
@FunctionalInterface
public interface TransactionCallback<T> {
    /**
     * 执行事务,如需回滚事务,只需要调用 tranStatus 的 setRollbackOnly() 方法即可。
     * 请注意:异常的抛出一会引起事务的回滚。 */
    T doTransaction(TransactionStatus tranStatus) throws Throwable;
}