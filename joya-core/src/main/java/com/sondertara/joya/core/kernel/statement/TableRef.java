package com.sondertara.joya.core.kernel.statement;

import java.util.Objects;

/**
 * 表引用，SQL 中一个物理表的不可变标识。
 * <p>
 * 由渲染层（{@code TableMeta}）直接给出，而非从 SQL 字符串正则提取，
 * 避免 {@code SqlFormatUtils} 正则替换带来的注入面与错位风险。
 *
 * @param name  物理表名（如 {@code t_user}）
 * @param alias 表别名（如 {@code t0}），无别名时为空字符串
 */
public record TableRef(String name, String alias) {

    public TableRef {
        Objects.requireNonNull(name, "table name must not be null");
        alias = alias == null ? "" : alias;
    }

    public static TableRef of(String name) {
        return new TableRef(name, "");
    }

    public static TableRef of(String name, String alias) {
        return new TableRef(name, alias);
    }

    /** 返回改写表名后的新实例，别名保持不变。 */
    public TableRef withName(String newName) {
        return new TableRef(newName, alias);
    }
}
