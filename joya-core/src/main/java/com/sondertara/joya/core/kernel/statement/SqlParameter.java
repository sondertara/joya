package com.sondertara.joya.core.kernel.statement;

import java.util.Objects;

/**
 * 有序 SQL 参数描述 —— 替代裸 {@code Object} 与旧 {@code PreparedParameterBinder}。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>{@code index} 从 1 开始连续递增，由 {@link SqlParameterList} 自动分配，禁止手写。</li>
 *   <li>{@code name} 保留参数逻辑名（列名/条件名），用于调试与审计。</li>
 *   <li>{@code source} 标识参数语义来源（SET/WHERE/TENANT...）。</li>
 *   <li>{@code binder} 负责 JDBC 绑定，默认复用现有成熟逻辑。</li>
 * </ul>
 *
 * @param index   参数位置（从 1 开始，对应 SQL 中第 index 个 ?）
 * @param name    参数逻辑名
 * @param value   参数值
 * @param source  参数来源
 * @param binder  绑定策略
 */
public record SqlParameter(
        int index,
        String name,
        Object value,
        ParameterSource source,
        ParameterBinder binder
) {

    public SqlParameter {
        Objects.checkIndex(index, Integer.MAX_VALUE);
        Objects.requireNonNull(source, "parameter source must not be null");
        Objects.requireNonNull(binder, "parameter binder must not be null");
        name = name == null ? "" : name;
    }

    /** 便捷构造：使用默认绑定策略。 */
    public static SqlParameter of(int index, String name, Object value, ParameterSource source) {
        return new SqlParameter(index, name, value, source, ParameterBinder.DEFAULT);
    }
}
