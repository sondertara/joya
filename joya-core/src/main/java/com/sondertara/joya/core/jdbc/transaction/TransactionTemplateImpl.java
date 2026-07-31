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

import com.sondertara.joya.core.jdbc.callback.TransactionCallback;

/**
 * {@link TransactionTemplate} 默认实现：包装 {@link TransactionManager}。
 * <p>
 * 行为：begin → 回调 → 抛错则标记 rollbackOnly → finally 由 manager 提交
 * （manager 内部检查 rollbackOnly 自动走回滚路径）。
 *
 * @author huangxiaohu
 */
public class TransactionTemplateImpl implements TransactionTemplate {

    private final TransactionManager transactionManager;

    public TransactionTemplateImpl(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public <T> T execute(TransactionCallback<T> callback, Propagation propagation, Isolation isolation) throws Throwable {
        TransactionStatus status = transactionManager.begin(propagation, isolation);
        try {
            return callback.doTransaction(status);
        } catch (Throwable ex) {
            if (status instanceof AbstractTransactionStatus) {
                ((AbstractTransactionStatus) status).markRollbackOnly();
            }
            throw ex;
        } finally {
            if (!status.isCompleted()) {
                transactionManager.commit(status);
            }
        }
    }
}
