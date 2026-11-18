package com.sondertara.joya.core.kernel.executor;

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
    public T extract(ResultSet record) throws SQLException {
        if (record.next()) {
            return rowMapper.forResultSet(record).map(record);
        }
        return null;
    }
}
