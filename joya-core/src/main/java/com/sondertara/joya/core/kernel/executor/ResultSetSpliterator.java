package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.exceptions.DbException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author huangxiaohu
 */
public class ResultSetSpliterator<T> implements Spliterator<T> {

    /** 事务流访问守卫；普通非事务流不安装守卫。 */
    public interface StreamAccess {
        void checkStreamAccess();

        void registerStream(Runnable closeAction);

        default void onStreamClosed() {
        }

        default void onStreamClosed(Runnable closeAction) {
            onStreamClosed();
        }
    }

    private final ResultSet rs;
    private final RowMapper<T> rowMapper;
    private final StreamAccess streamAccess;
    private final Function<SQLException, ? extends RuntimeException> exceptionTranslator;
    private RowMapper<T> boundMapper;

    public ResultSetSpliterator(ResultSet rs, RowMapper<T> rowMapper) {
        this(rs, rowMapper, null, DbException::new);
    }

    public ResultSetSpliterator(ResultSet rs, RowMapper<T> rowMapper, StreamAccess streamAccess) {
        this(rs, rowMapper, streamAccess, DbException::new);
    }

    public ResultSetSpliterator(ResultSet rs,
                                RowMapper<T> rowMapper,
                                StreamAccess streamAccess,
                                Function<SQLException, ? extends RuntimeException> exceptionTranslator) {
        this.rs = rs;
        this.rowMapper = rowMapper;
        this.streamAccess = streamAccess;
        this.exceptionTranslator = exceptionTranslator == null ? DbException::new : exceptionTranslator;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (streamAccess != null) {
            streamAccess.checkStreamAccess();
        }
        try {
            if (this.rs.next()) {
                if (this.boundMapper == null) {
                    this.boundMapper = this.rowMapper.forResultSet(this.rs);
                }
                action.accept(this.boundMapper.map(this.rs));
                return true;
            }
            return false;
        } catch (SQLException ex) {
            throw this.exceptionTranslator.apply(ex);
        }
    }

    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
        return Spliterator.ORDERED;
    }

    public Stream<T> stream() {
        return StreamSupport.stream(this, false);
    }
}