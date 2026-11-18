package com.sondertara.joya.core.kernel.executor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 将结果集转换成列表
 *
 * @author huangxiaohu
 */
public class ListRecordExtractor<T> implements ResultSetExtractor<List<T>> {
    private final RowMapper<T> rowMapper;

    public ListRecordExtractor(RowMapper<T> rowMapper) {
        this.rowMapper = rowMapper;
    }

    @Override
    public List<T> extract(ResultSet record) throws SQLException {
        List<T> result = new ArrayList<>();
        RowMapper<T> boundMapper = null;
        while (record.next()) {
            if (boundMapper == null) {
                boundMapper = rowMapper.forResultSet(record);
            }
            result.add(boundMapper.map(record));
        }
        return result;
    }
}
