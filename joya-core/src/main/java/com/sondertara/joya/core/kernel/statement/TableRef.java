package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 表引用，SQL 中一个物理表的不可变标识。
 *
 * <p>由渲染层直接给出，而非从 SQL 字符串正则提取，避免正则替换带来的注入面与错位风险。
 *
 * <p><b>schema 与 name 分离</b>：{@code app.t_user} 解析为 {@code schema="app"} + {@code name="t_user"}。
 * 这样分片规则拿到的始终是裸表名（{@code t_user}），改写时也不会把 schema 丢掉。
 *
 * @param name   物理表名（如 {@code t_user}），不含 schema
 * @param schema schema 名（如 {@code app}），无限定 schema 时为 {@code null}
 * @param alias  表别名（如 {@code t0}），无别名时为空字符串
 */
public record TableRef(String name, @Nullable String schema, String alias) {

    public TableRef {
        Objects.requireNonNull(name, "table name must not be null");
        alias = alias == null ? "" : alias;
        schema = (schema == null || schema.isBlank()) ? null : schema;
    }

    public static TableRef of(String name) {
        return new TableRef(name, null, "");
    }

    public static TableRef of(String name, String alias) {
        return new TableRef(name, null, alias);
    }

    public static TableRef of(@Nullable String schema, String name, String alias) {
        return new TableRef(name, schema, alias);
    }

    /**
     * 从可能带 schema 的限定名构造：按最后一个 {@code .} 拆分。
     *
     * <p>仅用于渲染层产出物（如 {@code app.t_user}）；带点号的引号标识符不在此列。
     */
    public static TableRef ofQualified(String qualifiedName, String alias) {
        Objects.requireNonNull(qualifiedName, "qualified table name must not be null");
        int dot = qualifiedName.lastIndexOf('.');
        if (dot < 0) {
            return new TableRef(qualifiedName, null, alias);
        }
        return new TableRef(qualifiedName.substring(dot + 1), qualifiedName.substring(0, dot), alias);
    }

    /** 带 schema 的完整表名；无 schema 时等同 {@link #name()}。 */
    public String qualifiedName() {
        return schema == null ? name : schema + "." + name;
    }
}
