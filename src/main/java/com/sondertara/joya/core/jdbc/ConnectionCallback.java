

package com.sondertara.joya.core.jdbc;

import org.springframework.lang.Nullable;

import java.sql.Connection;
import java.sql.SQLException;


@FunctionalInterface
public interface ConnectionCallback<T> {


    @Nullable
    T doInTransaction(Connection conn) throws SQLException;

}
