package com.sondertara.joya.core.kernel.observe;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;

import java.util.Collections;
import java.util.Map;

/**
 * 一次 SQL 执行的只读上下文，供 {@link SqlExecutionListener} 使用。
 *
 * <p>{@code attributes} 为单次物理执行的内部横切属性包：由准备阶段的 {@code SqlRewriter} / {@code SqlInterceptor}
 * 写入（如链路追踪标签、改写标记），在此以不可变视图暴露给观测监听器。
 */
public record SqlExecutionContext(
        SqlStatement statement,
        boolean batch,
        Map<String, Object> attributes
) {

    public SqlExecutionContext {
        statement = java.util.Objects.requireNonNull(statement, "statement must not be null");
        attributes = attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    public StatementMetadata metadata() {
        return statement.metadata();
    }
}
