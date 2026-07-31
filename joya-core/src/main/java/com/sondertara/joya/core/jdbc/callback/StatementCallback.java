package com.sondertara.joya.core.jdbc.callback;



import java.sql.SQLException;
import java.sql.Statement;


/**
 * @author SonderTara
 */
@FunctionalInterface
public interface StatementCallback<T> {

    /**
     * in  Statement
     *
     * @param stmt sql Statement
     * @return result
     * @throws SQLException e
     */
    T doInStatement(Statement stmt) throws SQLException;

}
