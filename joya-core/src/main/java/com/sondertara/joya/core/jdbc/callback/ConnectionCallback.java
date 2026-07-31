package com.sondertara.joya.core.jdbc.callback;


import java.sql.Connection;
import java.sql.SQLException;

/**
 * @param <T> the type result
 * @author SonderTara
 */
@FunctionalInterface
public interface ConnectionCallback<T> {

    /**
     * do in transaction
     *
     * @param conn sql Connection
     * @return result
     * @throws SQLException e
     */
    T doWork(Connection conn) throws SQLException;

}
