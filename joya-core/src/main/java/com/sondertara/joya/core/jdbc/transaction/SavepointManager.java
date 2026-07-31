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
import java.sql.Savepoint;

/**
 * 保存点管理器接口。
 * <p>
 * 由 {@link com.sondertara.joya.core.jdbc.support.ConnectionContext} 实现，
 * 事务管理器借此完成 NESTED 传播的保存点操作。
 *
 * @author huangxiaohu
 */
public interface SavepointManager {
    /** 当前连接是否支持保存点 */
    boolean supportSavepoint() throws SQLException;

    /** 创建一个具名保存点 */
    Savepoint createSavepoint() throws SQLException;

    /** 释放指定保存点 */
    void releaseSavepoint(Savepoint savepoint) throws SQLException;

    /** 回滚到指定保存点 */
    void rollback(Savepoint savepoint) throws SQLException;
}
