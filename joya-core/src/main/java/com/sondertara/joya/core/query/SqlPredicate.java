package com.sondertara.joya.core.query;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;

/**
 * 延迟求值的 SQL 谓词节点（Where / Having 条件树单元），唯一真相源是结构化 {@link SqlNode}。
 *
 * <p>在 API 调用期仅捕获 {@link ColumnMetaProvider} 与参数值；运行期通过 {@link RenderContext}
 * 产出 AST 叶子，由 {@link com.sondertara.joya.core.kernel.statement.SqlRenderer} 统一渲染。
 *
 * @author huangxiaohu
 */
@FunctionalInterface
public interface SqlPredicate {

    /**
     * 产出 AST 叶子节点。
     *
     * @param ctx    查询渲染上下文（负责解析表别名与校验表归属）
     * @param source 参数来源标记（如 {@link ParameterSource#WHERE}）
     */
    SqlNode toNode(RenderContext ctx, ParameterSource source);
}
