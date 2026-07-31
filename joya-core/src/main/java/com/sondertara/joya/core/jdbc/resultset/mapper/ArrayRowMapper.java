package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.SqlDataUtils;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * 将一行数据转换成数组
 *
 * @author huangxiaohu
 */
@Slf4j
public class ArrayRowMapper implements RowMapper<Object[]> {
    @Override
    public Object[] map(ResultSet row) {
        try {
            ResultSetMetaData metaData = row.getMetaData();

            int count = metaData.getColumnCount();
            Object[] objects = new Object[count];
            for (int i = 1; i <= count; i++) {
                Object value = SqlDataUtils.getResultSetValue(row, i);
                objects[i - 1] = value;
            }
            return objects;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }
}
