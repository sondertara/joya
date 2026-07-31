package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.exceptions.DbException;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 转换只有一行的结果集
 *
 * @author huangxiaohu
 */
public class SingleRowRecordExtractor<T> implements ResultSetExtractor<T> {
    private final RowMapper<T> rowMapper;

    public SingleRowRecordExtractor(RowMapper<T> rowMapper) {
        this.rowMapper = rowMapper;
    }

    @Override
    public T extract(ResultSet record) {
        try {
            if (record.next()) {
                return rowMapper.map(record);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return null;
    }
}
