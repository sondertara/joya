package com.sondertara.joya.core.jdbc.resultset.mapper;



import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
@FunctionalInterface
public interface ResultSetExtractor<T> {
    @Nullable
    T extract(@NonNull ResultSet rs) throws SQLException;
}
