package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * {@link ResultSet} 流式迭代器：支持事务所有者线程守卫，并在游标耗尽或迭代异常时即时释放底层 JDBC 资源。
 */
public class ResultSetSpliterator<T> implements Spliterator<T> {

    private final ResultSet rs;
    private final RowMapper<T> rowMapper;
    private final @Nullable Runnable accessCheck;
    private final Function<SQLException, ? extends RuntimeException> exceptionTranslator;
    private final @Nullable Runnable eagerResourceRelease;
    private @Nullable RowMapper<T> boundMapper;

    public ResultSetSpliterator(ResultSet rs, RowMapper<T> rowMapper) {
        this(rs, rowMapper, null, DbException::new, null);
    }

    public ResultSetSpliterator(ResultSet rs,
                                RowMapper<T> rowMapper,
                                @Nullable Runnable accessCheck,
                                Function<SQLException, ? extends RuntimeException> exceptionTranslator,
                                @Nullable Runnable eagerResourceRelease) {
        this.rs = rs;
        this.rowMapper = rowMapper;
        this.accessCheck = accessCheck;
        this.exceptionTranslator = exceptionTranslator == null ? DbException::new : exceptionTranslator;
        this.eagerResourceRelease = eagerResourceRelease;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (accessCheck != null) {
            accessCheck.run();
        }
        try {
            if (this.rs.next()) {
                if (this.boundMapper == null) {
                    this.boundMapper = this.rowMapper.forResultSet(this.rs);
                }
                action.accept(this.boundMapper.map(this.rs));
                return true;
            }
            if (eagerResourceRelease != null) {
                eagerResourceRelease.run();
            }
            return false;
        } catch (SQLException ex) {
            RuntimeException translated = this.exceptionTranslator.apply(ex);
            releaseOnIterationFailure(translated);
            throw translated;
        } catch (Throwable t) {
            releaseOnIterationFailure(t);
            throw t;
        }
    }

    private void releaseOnIterationFailure(Throwable primary) {
        if (eagerResourceRelease != null) {
            try {
                eagerResourceRelease.run();
            } catch (Throwable cleanupErr) {
                primary.addSuppressed(cleanupErr);
            }
        }
    }

    @Override
    public @Nullable Spliterator<T> trySplit() {
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
