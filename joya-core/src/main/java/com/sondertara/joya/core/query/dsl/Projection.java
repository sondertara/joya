package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.query.RenderContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次执行的投影计划：SELECT 列表 + 结果类型描述（或直接映射器）。
 *
 * @param columns     SELECT 列表的 SQL 片段（用于日志/调试）
 * @param nodes       SELECT 列表的 AST 节点
 * @param resultType  结果类型分类
 * @param resultClass 目标 Java 类型
 * @param mapper      自定义映射器（与类型描述二选一）
 * @param aggregate   是否包含聚合表达式
 * @param context     构建该投影时绑定的渲染上下文（避免同一终结操作重复构造 RenderContext）
 */
record Projection<V>(List<String> columns,
                     List<SqlNode> nodes,
                     @Nullable ResultType resultType,
                     @Nullable Class<V> resultClass,
                     @Nullable RowMapper<V> mapper,
                     boolean aggregate,
                     @Nullable RenderContext context) {

    Projection(List<String> columns,
               List<SqlNode> nodes,
               @Nullable ResultType resultType,
               @Nullable Class<V> resultClass,
               @Nullable RowMapper<V> mapper,
               boolean aggregate) {
        this(columns, nodes, resultType, resultClass, mapper, aggregate, null);
    }

    Projection {
        columns = List.copyOf(columns);
        if (nodes == null) {
            List<SqlNode> rawNodes = new ArrayList<>(columns.size());
            for (String column : columns) {
                rawNodes.add(SqlNodes.raw(column));
            }
            nodes = List.copyOf(rawNodes);
        } else {
            nodes = List.copyOf(nodes);
        }
        boolean typed = resultType != null && resultClass != null;
        if (typed == (mapper != null)) {
            throw new IllegalArgumentException(
                    "Projection must declare exactly one of (resultType + resultClass) or mapper");
        }
    }

    static <V> Projection<V> typed(List<String> columns, ResultType resultType, Class<V> resultClass) {
        return typed(columns, resultType, resultClass, false);
    }

    static <V> Projection<V> typed(List<String> columns, ResultType resultType,
                                   Class<V> resultClass, boolean aggregate) {
        return new Projection<>(columns, null, resultType, resultClass, null, aggregate, null);
    }

    static <V> Projection<V> typed(List<String> columns, List<SqlNode> nodes,
                                   ResultType resultType, Class<V> resultClass, boolean aggregate) {
        return new Projection<>(columns, nodes, resultType, resultClass, null, aggregate, null);
    }

    static <V> Projection<V> typed(List<String> columns, List<SqlNode> nodes,
                                   ResultType resultType, Class<V> resultClass, boolean aggregate,
                                   @Nullable RenderContext context) {
        return new Projection<>(columns, nodes, resultType, resultClass, null, aggregate, context);
    }

    static <V> Projection<V> mapped(List<String> columns, RowMapper<V> mapper) {
        return mapped(columns, mapper, false);
    }

    static <V> Projection<V> mapped(List<String> columns, RowMapper<V> mapper, boolean aggregate) {
        return new Projection<>(columns, null, null, null, mapper, aggregate, null);
    }

    static <V> Projection<V> mapped(List<String> columns, List<SqlNode> nodes,
                                    RowMapper<V> mapper, boolean aggregate) {
        return new Projection<>(columns, nodes, null, null, mapper, aggregate, null);
    }

    static <V> Projection<V> mapped(List<String> columns, List<SqlNode> nodes,
                                    RowMapper<V> mapper, boolean aggregate,
                                    @Nullable RenderContext context) {
        return new Projection<>(columns, nodes, null, null, mapper, aggregate, context);
    }
}
