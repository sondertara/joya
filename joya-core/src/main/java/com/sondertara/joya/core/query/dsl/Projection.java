package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.query.TableScope;
import com.sondertara.joya.utils.SqlDataUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 一次查询的投影计划：SELECT 列表 AST 节点 + 结果类型描述（或直接映射器）。
 */
record Projection<V>(List<SqlNode> nodes,
                     @Nullable ResultType resultType,
                     @Nullable Class<V> resultClass,
                     @Nullable RowMapper<V> mapper,
                     Map<String, SqlDataUtils.ColumnReader> columnReaders,
                     boolean aggregate,
                     @Nullable TableScope scope) {

    Projection {
        nodes = List.copyOf(nodes);
        columnReaders = columnReaders == null || columnReaders.isEmpty() ? Map.of() : Map.copyOf(columnReaders);
        boolean typed = resultType != null && resultClass != null;
        if (typed == (mapper != null)) {
            throw new IllegalArgumentException(
                    "Projection must declare exactly one of (resultType + resultClass) or mapper");
        }
    }

    static <V> Projection<V> typed(List<SqlNode> nodes,
                                   ResultType resultType, Class<V> resultClass, boolean aggregate,
                                   @Nullable TableScope scope) {
        return new Projection<>(nodes, resultType, resultClass, null, Map.of(), aggregate, scope);
    }

    static <V> Projection<V> typed(List<SqlNode> nodes,
                                   ResultType resultType,
                                   Class<V> resultClass,
                                   Map<String, SqlDataUtils.ColumnReader> columnReaders,
                                   boolean aggregate,
                                   @Nullable TableScope scope) {
        return new Projection<>(nodes, resultType, resultClass, null, columnReaders, aggregate, scope);
    }

    static <V> Projection<V> mapped(List<SqlNode> nodes,
                                    RowMapper<V> mapper, boolean aggregate,
                                    @Nullable TableScope scope) {
        return new Projection<>(nodes, null, null, mapper, Map.of(), aggregate, scope);
    }
}
