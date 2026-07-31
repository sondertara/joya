/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.transaction;

import java.sql.SQLException;

/**
 * 数据源事务管理器接口。
 * <p>
 * 职责：开启/递交/回滚事务，通过 {@link AbstractTransactionStatus} 跟踪栈状态。
 * 无参的 {@code commit() / rollback()} 已移除 —— 只能通过 {@link TransactionStatus}
 * 引用来操作，避免栈操作混乱。
 *
 * @author huangxiaohu
 */
public interface TransactionManager {

    /**
     * 开启事务，使用默认传播行为与隔离级别。
     */
    default TransactionStatus begin() throws SQLException {
        return begin(Propagation.REQUIRED, Isolation.DEFAULT);
    }

    /**
     * 开启事务，指定传播行为。
     */
    default TransactionStatus begin(Propagation propagation) throws SQLException {
        return begin(propagation, Isolation.DEFAULT);
    }

    /**
     * 开启事务。
     *
     * @param propagation 传播行为
     * @param isolation   隔离级别（{@link Isolation#DEFAULT} 表示不主动设置）
     * @return 事务状态只读视图
     */
    TransactionStatus begin(Propagation propagation, Isolation isolation) throws SQLException;

    /**
     * 递交事务。若状态不在栈顶，会先递交其上的所有事务。
     */
    void commit(TransactionStatus status) throws SQLException;

    /**
     * 回滚事务。若状态不在栈顶，会先回滚其上的所有事务。
     */
    void rollback(TransactionStatus status) throws SQLException;

    /**
     * 当前管理器是否存在任何未完成的事务。
     */
    boolean hasTransaction();

    /**
     * 给定状态是否为当前栈顶。
     */
    boolean isTopTransaction(TransactionStatus status);
}
