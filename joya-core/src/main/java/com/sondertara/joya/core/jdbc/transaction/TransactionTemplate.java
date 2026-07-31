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
 * 编程式事务模板。
 * <p>
 * 接口只剩一个核心 {@link #execute(TransactionCallback, Propagation, Isolation)}，
 * 其余 default 方法为重载糖。原 {@code TransactionTemplateManager}
 * 已合并到默认实现里，不再单独建类。
 *
 * @author huangxiaohu
 */
public interface TransactionTemplate {

    /**
     * 使用默认传播行为、默认隔离级别执行事务回调。
     */
    default <T> T execute(TransactionCallback<T> callback) throws Throwable {
        return execute(callback, Propagation.REQUIRED, Isolation.DEFAULT);
    }

    /**
     * 使用指定传播行为、默认隔离级别执行事务回调。
     */
    default <T> T execute(TransactionCallback<T> callback, Propagation propagation) throws Throwable {
        return execute(callback, propagation, Isolation.DEFAULT);
    }

    /**
     * 执行事务回调：开启事务、执行回调、捕获异常标记回滚、最终递交或回滚。
     */
    <T> T execute(TransactionCallback<T> callback, Propagation propagation, Isolation isolation) throws Throwable;
}
