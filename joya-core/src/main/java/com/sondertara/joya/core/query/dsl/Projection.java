package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.query.RenderContext;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 一次执行的投影计划：SELECT 列表 AST 节点 + 结果类型描述（或直接映射器）。
 *
 * @param nodes       SELECT 列表的 AST 节点
 * @param resultType  结果类型分类
 * @param resultClass 目标 Java 类型
 * @param mapper      自定义映射器（与类型描述二选一）
 * @param aggregate   是否包含聚合表达式
 * @param context     构建该投影时绑定的渲染上下文（避免同一终结操作重复构造 RenderContext）
 */
record Projection<V>(List<SqlNode> nodes,
                     @Nullable ResultType resultType,
                     @Nullable Class<V> resultClass,
                     @Nullable RowMapper<V> mapper,
                     boolean aggregate,
                     @Nullable RenderContext context) {

    Projection {
        nodes = List.copyOf(nodes);
        boolean typed = resultType != null && resultClass != null;
        if (typed == (mapper != null)) {
            throw new IllegalArgumentException(
                    "Projection must declare exactly one of (resultType + resultClass) or mapper");
        }
    }

    static <V> Projection<V> typed(List<SqlNode> nodes,
                                   ResultType resultType, Class<V> resultClass, boolean aggregate,
                                   @Nullable RenderContext context) {
        return new Projection<>(nodes, resultType, resultClass, null, aggregate, context);
    }

    static <V> Projection<V> mapped(List<SqlNode> nodes,
                                    RowMapper<V> mapper, boolean aggregate,
                                    @Nullable RenderContext context) {
        return new Projection<>(nodes, null, null, mapper, aggregate, context);
    }
}
