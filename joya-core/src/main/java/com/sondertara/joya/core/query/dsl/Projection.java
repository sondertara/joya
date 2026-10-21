package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;

import java.util.List;

/**
 * 一次执行的投影计划：SELECT 列表（已渲染、含标签）与对应的行映射器。
 *
 * @param columns SELECT 列表片段，例如 {@code t0.name AS name}
 * @param mapper  行映射器
 * @param <V>     行结果类型
 */
record Projection<V>(List<String> columns, RowMapper<V> mapper) {

    Projection {
        columns = List.copyOf(columns);
    }
}
