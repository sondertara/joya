package com.sondertara.joya.core.kernel.executor;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集单行映射器。
 *
 * @author huangxiaohu
 */
public interface RowMapper<T> {

    /**
     * 将当前行转换为目标对象。
     *
     * @param record 当前定位到有效行的 {@link ResultSet}
     * @return 目标对象
     */
    T map(ResultSet record);

    /**
     * 针对给定 {@link ResultSet} 预解析一次列元数据布局并返回绑定后的行映射器（多行查询/流式查询热路径优化）。
     *
     * <p>默认实现直接返回 {@code this}（无状态映射器无需重写）。
     *
     * @param rs 已打开的结果集
     * @return 绑定了当前结果集列布局的行映射器
     */
    default RowMapper<T> forResultSet(ResultSet rs) throws SQLException {
        return this;
    }
}
