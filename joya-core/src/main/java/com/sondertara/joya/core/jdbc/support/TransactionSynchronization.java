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
package com.sondertara.joya.core.jdbc.support;

import com.sondertara.joya.exceptions.DbException;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 线程级连接同步器。每个线程、每个 DataSource 维护一个 {@link ConnectionContext}。
 * <p>
 * 替代旧的 {@code DataSourceUtils.HOLDER_MAP} + {@code ConnectionHolder} 体系。
 * 所有方法均为 {@code static}，因为连接绑定是全局的线程局部状态。
 * <p>
 * 职责：
 * <ul>
 *   <li>线程绑定的 ConnectionContext 获取/创建/清除</li>
 *   <li>事务挂起/恢复时替换线程绑定</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public final class TransactionSynchronization {

    private static final ThreadLocal<Map<DataSource, ConnectionContext>> CONTEXT_MAP =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private TransactionSynchronization() {
    }

    /**
     * 获取或创建当前线程、指定数据源的 ConnectionContext。
     */
    public static ConnectionContext getOrCreate(DataSource dataSource) {
        return CONTEXT_MAP.get().computeIfAbsent(dataSource, ConnectionContext::new);
    }

    /**
     * 获取当前线程、指定数据源的 ConnectionContext，不存在时返回 {@code null}。
     */
    public static ConnectionContext getCurrent(DataSource dataSource) {
        return CONTEXT_MAP.get().get(dataSource);
    }

    /**
     * 强制设置当前线程、指定数据源的 ConnectionContext。
     */
    public static void bind(DataSource dataSource, ConnectionContext ctx) {
        CONTEXT_MAP.get().put(dataSource, ctx);
    }

    /**
     * 移除当前线程、指定数据源的 ConnectionContext。
     */
    public static void clear(DataSource dataSource) {
        CONTEXT_MAP.get().remove(dataSource);
    }

    /**
     * 挂起当前线程、指定数据源的连接上下文（清除线程绑定）。
     *
     * @return 被挂起的上下文，用于后续 {@link #resume(DataSource, ConnectionContext)} 恢复
     */
    public static ConnectionContext suspend(DataSource dataSource) {
        return CONTEXT_MAP.get().remove(dataSource);
    }

    /**
     * 恢复被挂起的连接上下文到当前线程。
     */
    public static void resume(DataSource dataSource, ConnectionContext ctx) {
        CONTEXT_MAP.get().put(dataSource, ctx);
    }

    /**
     * 当前线程、指定数据源是否绑定了连接上下文。
     */
    public static boolean isBound(DataSource dataSource) {
        return CONTEXT_MAP.get().containsKey(dataSource);
    }
    /**
     * 当前线程、指定数据源是否处于事务中。
     */
    public static boolean isInTransaction(DataSource dataSource) {
        ConnectionContext ctx = CONTEXT_MAP.get().get(dataSource);
        return ctx != null && ctx.hasTransaction();
    }

    /**
     * 释放一次对当前线程、指定数据源绑定的 ConnectionContext 的引用。
     * 引用计数归零且不在事务中时自动清理线程绑定并关闭连接。
     */
    public static void release(DataSource dataSource) {
        ConnectionContext ctx = getCurrent(dataSource);
        if (ctx == null) {
            return;
        }
        try {
            ctx.release();
        } catch (SQLException e) {
            throw new DbException(e);
        }
        if (ctx.getReferenceCount() <= 0 && !ctx.hasTransaction()) {
            clear(dataSource);
        }
    }
}
