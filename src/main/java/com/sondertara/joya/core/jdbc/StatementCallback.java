

package com.sondertara.joya.core.jdbc;

import org.springframework.lang.Nullable;

import java.sql.SQLException;
import java.sql.Statement;


/**
 * @author SonderTara
 */
@FunctionalInterface
public interface StatementCallback<T> {


    @Nullable
    T doInStatement(Statement stmt) throws SQLException;

}
