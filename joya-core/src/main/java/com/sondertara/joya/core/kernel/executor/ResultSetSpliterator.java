package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.executor.RowMapper;

import com.sondertara.joya.exceptions.DbException;


import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author huangxiaohu
 */
public  class ResultSetSpliterator<T> implements Spliterator<T> {

		private final ResultSet rs;

		private final RowMapper<T> rowMapper;



		public ResultSetSpliterator(ResultSet rs, RowMapper<T> rowMapper) {
			this.rs = rs;
			this.rowMapper = rowMapper;
		}

		@Override
		public boolean tryAdvance(Consumer<? super T> action) {
			try {
				if (this.rs.next()) {
					action.accept(this.rowMapper.map(this.rs));
					return true;
				}
				return false;
			}
			catch (SQLException ex) {
				throw new DbException(ex);
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