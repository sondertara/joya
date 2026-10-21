package com.sondertara.joya.core.kernel.statement;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数收集器：自动维护参数顺序，禁止调用方手写 {@code index}。
 * <p>
 * 调用方只声明参数名、值与来源，收集器按添加顺序自动分配从 1 开始的连续 {@code index}。
 * 最终不变量：{@code SqlParameter.index} 连续递增，SQL 中第 N 个 ? 对应 {@code parameters[N-1]}。
 */
public final class SqlParameterList {

    private final List<SqlParameter> parameters = new ArrayList<>();

    /**
     * 追加一个参数，自动分配 index。
     *
     * @return 该参数在 SQL 中对应的占位符 {@code ?}（始终返回 "?"，保留扩展命名参数能力）
     */
    public String add(String name, Object value, ParameterSource source) {
        int index = parameters.size() + 1;
        parameters.add(SqlParameter.of(index, name, value, source));
        return "?";
    }

    /** 当前参数数量。 */
    public int size() {
        return parameters.size();
    }

    /** 导出不可变参数列表。 */
    public List<SqlParameter> toList() {
        return List.copyOf(parameters);
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return parameters.isEmpty();
    }
}
