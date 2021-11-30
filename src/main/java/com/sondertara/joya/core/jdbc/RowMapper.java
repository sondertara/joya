package com.sondertara.joya.core.jdbc;

/**
 * 行转换器接口
 *
 * @author huangxiaohu
 */
public interface RowMapper<T> {
    T map(Row row);
}
