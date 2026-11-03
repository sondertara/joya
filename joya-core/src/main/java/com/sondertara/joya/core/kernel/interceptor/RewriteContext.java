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

    /** 属性键：值为 {@link Boolean#TRUE} 时表示当前语句是批量模板探针（参数为空，整批只触发一次）。 */
    public static final String ATTR_BATCH = "joya.batch";

    /** 属性键：批量模式下的总行数（{@link Integer}）。 */
    public static final String ATTR_BATCH_ROWS = "joya.batch.rows";

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

    /**
     * 当前是否处于批量模式：语句是整批共用的 SQL 模板探针，参数为空，
     * 拦截器只允许改写文本（hint / 注释），不得追加参数或改变占位符个数。
     */
    public boolean isBatch() {
        return Boolean.TRUE.equals(attrs.get(ATTR_BATCH));
    }
}
