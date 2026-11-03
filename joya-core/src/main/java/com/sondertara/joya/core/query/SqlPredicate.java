package com.sondertara.joya.core.query;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;

/**
 * 延迟求值的 SQL 谓词节点（Where / Having 条件树单元）。
 *
 * <p>在 API 调用期仅捕获 {@link ColumnMetaProvider} 与参数值，不做别名翻译或序号重写；
 * 在运行期（{@code toSqlStatement()}）通过 {@link RenderContext} 单趟直接追加 SQL 片段与有序参数。
 *
 * @author huangxiaohu
 */
@FunctionalInterface
public interface SqlPredicate {

    /**
     * 将当前谓词渲染到目标 SQL 缓冲与参数列表中。
     *
     * @param ctx    查询渲染上下文（负责解析表别名与校验表归属）
     * @param sql    目标 SQL 缓冲区
     * @param params 目标有序参数列表
     * @param source 参数来源标记（如 {@link ParameterSource#WHERE}）
     */
    void render(RenderContext ctx, StringBuilder sql, SqlParameterList params, ParameterSource source);

    /**
     * 产出 AST 叶子节点；默认把当前谓词渲染为 Raw，具体谓词可覆盖为结构化节点。
     */
    default SqlNode toNode(RenderContext ctx, ParameterSource source) {
        StringBuilder sql = new StringBuilder();
        SqlParameterList params = new SqlParameterList();
        render(ctx, sql, params, source);
        return sql.isEmpty() ? null : SqlNodes.raw(sql.toString(), params.toList());
    }
}
