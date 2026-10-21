package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.jdbc.support.JdbcType;

import java.util.Objects;

/**
 * 列级绑定描述 —— 批处理场景下一列共享一个实例，而非逐值 new。
 * <p>
 * 批处理的本质是「同构行」：每一行的列结构完全一致。因此类型信息与绑定策略
 * 在列级声明一次即可，整批所有行共用同一个 {@link ColumnBinding} 实例，
 * 避免逐值包装带来的 GC 压力。
 *
 * @param name    列名（逻辑名，用于调试）
 * @param jdbcType JDBC 类型，{@link JdbcType#UNKNOWN} 表示由驱动推断
 * @param binder  绑定策略，默认复用现有成熟逻辑
 */
public record ColumnBinding(
        String name,
        JdbcType jdbcType,
        ParameterBinder binder
) {

    public ColumnBinding {
        Objects.requireNonNull(name, "column name must not be null");
        Objects.requireNonNull(binder, "column binder must not be null");
    }

    /** 便捷构造：使用默认绑定策略。 */
    public static ColumnBinding of(String name, JdbcType jdbcType) {
        return new ColumnBinding(name, jdbcType, ParameterBinder.DEFAULT);
    }

    /** 便捷构造：未知类型 + 默认绑定。 */
    public static ColumnBinding of(String name) {
        return new ColumnBinding(name, JdbcType.UNKNOWN, ParameterBinder.DEFAULT);
    }
}
