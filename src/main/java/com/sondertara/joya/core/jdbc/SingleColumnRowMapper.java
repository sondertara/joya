package com.sondertara.joya.core.jdbc;

/**
 * 转换只有一个列的数据行
 *
 * @author huangxiaohu
 */
public class SingleColumnRowMapper<T> implements RowMapper<T> {
    @Override
    @SuppressWarnings("unchecked")
    public T map(Row row) {
        return (T) row.getObject(1);
    }
}
