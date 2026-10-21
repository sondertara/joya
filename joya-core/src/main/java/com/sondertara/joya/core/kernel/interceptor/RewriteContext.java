package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.Collections;
import java.util.Map;

/**
 * SQL 改写上下文 —— 拦截器链的共享可变载体。
 * <p>
 * 设计：{@code original} 为不可变快照（供审计对比），{@code current} 为可变指针，
 * 拦截器通过 {@link #rewrite(SqlStatement)} 推进。{@code attrs} 为请求级透传（租户/分片键）。
 *
 * @param original 改写前的原始语句快照
 * @param attrs    请求级属性，如 {@code tenantId}、{@code shardKey}
 */
public final class RewriteContext {
    private final SqlStatement original;
    private final Map<String, Object> attrs;
    private SqlStatement current;

    public RewriteContext(SqlStatement original, Map<String, Object> attrs) {
        this.original = original;
        this.current = original;
        this.attrs = attrs == null ? Collections.emptyMap() : attrs;
    }

    public SqlStatement original() {
        return original;
    }

    public SqlStatement statement() {
        return current;
    }

    /** 拦截器调用此方法推进改写结果。 */
    public void rewrite(SqlStatement next) {
        this.current = next;
    }

    @SuppressWarnings("unchecked")
    public <V> V attr(String key) {
        return (V) attrs.get(key);
    }

    public Map<String, Object> attrs() {
        return attrs;
    }
}
