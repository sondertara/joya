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
package com.sondertara.joya.core.tx;

import java.sql.Connection;

/**
 * JDBC 事务隔离级别。
 * <p>
 * {@link #DEFAULT} 表示使用数据源/驱动的默认隔离级别 —— 不主动调用
 * {@link Connection#setTransactionIsolation(int)}。它与 JDBC 的
 * {@link Connection#TRANSACTION_NONE}（意味着 "不支持事务"）语义完全不同，
 * 故本枚举不与 {@code TRANSACTION_NONE} 对应。
 *
 * @author huangxiaohu
 */
public enum Isolation {
    /**
     * 默认隔离级别：不主动设置，沿用数据源当前配置。
     */
    DEFAULT(-1),
    /**
     * 脏读：允许读取未提交的数据。
     *
     * @see Connection#TRANSACTION_READ_UNCOMMITTED
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
    /**
     * 不可重复读：只允许读取已提交的数据。
     *
     * @see Connection#TRANSACTION_READ_COMMITTED
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    /**
     * 可重复读：同一事务内多次读取结果一致。
     *
     * @see Connection#TRANSACTION_REPEATABLE_READ
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    /**
     * 串行化：最严格的隔离级别，事务依次执行。
     *
     * @see Connection#TRANSACTION_SERIALIZABLE
     */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int value;

    Isolation(int value) {
        this.value = value;
    }

    /**
     * 返回对应的 JDBC 隔离级别常量。
     * <p>
     * {@link #DEFAULT} 返回 {@code -1}，调用方需自行判断避开
     * {@link Connection#setTransactionIsolation(int)}。
     */
    public int getValue() {
        return value;
    }

    /**
     * 是否为 {@link #DEFAULT}（不主动设置隔离级别）。
     */
    public boolean isDefault() {
        return this == DEFAULT;
    }

    /**
     * 由 JDBC 隔离级别常量反查枚举。
     *
     * @throws IllegalArgumentException 遇到未知常量
     */
    public static Isolation fromJdbcValue(int value) {
        for (Isolation iso : values()) {
            if (iso.value == value) {
                return iso;
            }
        }
        throw new IllegalArgumentException("Unknown JDBC isolation level: " + value);
    }
}
