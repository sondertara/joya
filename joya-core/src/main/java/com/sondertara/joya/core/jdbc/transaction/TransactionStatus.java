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

/**
 * 事务状态的对外只读视图。
 * <p>
 * 调用方（如 {@link TransactionCallback}）只能查询状态，无法直接修改。
 * 写入操作（标记回滚、标记只读）由 {@link TransactionManager} 内部使用
 * {@link AbstractTransactionStatus} 完成。
 * <p>
 * 设计：接口只暴露读，避免业务代码越过事务管理器直接操纵状态机。
 *
 * @author huangxiaohu
 */
public interface TransactionStatus {
    /** 事务传播行为 */
    Propagation getPropagation();

    /** 隔离级别，{@code null} 表示未显式指定 */
    Isolation getIsolationLevel();

    /** 事务是否已经完成（递交或回滚） */
    boolean isCompleted();

    /** 是否被标记为仅回滚 */
    boolean isRollbackOnly();

    /** 是否为只读事务 */
    boolean isReadOnly();

    /** 是否使用了一个全新连接开启事务 */
    boolean isNewConnection();

    /** 是否为挂起 outer 事务后启动的事务 */
    boolean isSuspend();

    /** 是否携带保存点（NESTED 传播） */
    boolean hasSavepoint();
}
