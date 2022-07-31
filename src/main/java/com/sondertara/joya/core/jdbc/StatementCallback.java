package com.sondertara.joya.core.jdbc;

import org.springframework.lang.Nullable;

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
    @Nullable
    T doInStatement(Statement stmt) throws SQLException;

}
