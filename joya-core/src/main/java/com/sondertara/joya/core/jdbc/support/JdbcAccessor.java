
package com.sondertara.joya.core.jdbc.support;

import com.sondertara.joya.exceptions.DbException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;


/**
 * @author huangxiaohu
 */
public class JdbcAccessor {
    protected DataSource        dataSource;


    /**Return the DataSource used by this template.*/
    public DataSource getDataSource() {
        return this.dataSource;
    }

    /**Set the JDBC DataSource to obtain connections from.*/
    public void setDataSource(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**Return the Connection used by this template.*/
    public Connection getConnection() {
        assert this.dataSource!=null;
        try {
            return this.dataSource.getConnection();
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

}