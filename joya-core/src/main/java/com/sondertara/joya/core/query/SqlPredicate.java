package com.sondertara.joya.core.query;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;

/**
 * 延迟求值的 SQL 谓词节点（Where / Having 条件树单元），唯一真相源是结构化 {@link SqlNode}。
 */
@FunctionalInterface
public interface SqlPredicate {

    /**
     * 在给定符号作用域内产出结构化 AST 谓词节点。
     *
     * @param scope  符号解析作用域（负责分配表别名与校验表归属）
     * @param source 参数来源标记（如 {@link ParameterSource#WHERE}）
     */
    SqlNode toNode(TableScope scope, ParameterSource source);
}
