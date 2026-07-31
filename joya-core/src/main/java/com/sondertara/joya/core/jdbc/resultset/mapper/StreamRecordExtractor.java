package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * @author huangxiaohu
 */
public class StreamRecordExtractor<T> implements ResultSetExtractor<Stream<T>> {

    private final RowMapper<T> rowMapper;

    @Nullable
    private Runnable runnable;


    public StreamRecordExtractor(RowMapper<T> rowMapper) {
        this.rowMapper = rowMapper;
    }

    public void onClose(Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    public Stream<T> extract(ResultSet rs) {
        if (runnable == null) {
            runnable = () -> {
                try {
                    if (rs != null) {
                        rs.close();
                    }
                } catch (SQLException e) {
                    throw new DbException(e);
                }
            };
        }
        return new ResultSetSpliterator<>(rs, rowMapper).stream().onClose(runnable);
    }
}
