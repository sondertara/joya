package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.exceptions.DbException;

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
    public List<T> extract(ResultSet record) {
        List<T> result = new ArrayList<>();
        try {
            while (record.next()) {
                result.add(rowMapper.map(record));
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return result;
    }
}
