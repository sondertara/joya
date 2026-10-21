/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sondertara.joya.utils;

import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.core.jdbc.callback.DatabaseMetaDataCallback;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;


/**
 * fork from spring jdbc
 * Generic utility methods for working with JDBC. Mainly for internal use
 * within the framework, but also useful for custom JDBC access code.
 *
 * @author Thomas Risberg
 * @author Juergen Hoeller
 */
public abstract class JdbcUtils {

    /**
     * Constant that indicates an unknown (or unspecified) SQL type.
     *
     * @see Types
     */
    public static final int TYPE_UNKNOWN = Integer.MIN_VALUE;

    private static final Logger logger = LoggerFactory.getLogger(JdbcUtils.class);

    private static final Map<Integer, String> typeNames = new HashMap<>();

    static {
        try {
            for (Field field : Types.class.getFields()) {
                typeNames.put((Integer) field.get(null), field.getName());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve JDBC Types constants", ex);
        }
    }


    /**
     * Close the given JDBC Connection and ignore any thrown exception.
     * This is useful for typical finally blocks in manual JDBC code.
     *
     * @param con the JDBC Connection to close (may be {@code null})
     */
    public static void closeConnection(@Nullable Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException ex) {
                logger.debug("Could not close JDBC Connection", ex);
            } catch (Throwable ex) {
                // We don't trust the JDBC driver: It might throw RuntimeException or Error.
                logger.debug("Unexpected exception on closing JDBC Connection", ex);
            }
        }
    }

    /**
     * Close the given JDBC Statement and ignore any thrown exception.
     * This is useful for typical finally blocks in manual JDBC code.
     *
     * @param stmt the JDBC Statement to close (may be {@code null})
     */
    public static void closeStatement(@Nullable Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ex) {
                logger.trace("Could not close JDBC Statement", ex);
            } catch (Throwable ex) {
                // We don't trust the JDBC driver: It might throw RuntimeException or Error.
                logger.trace("Unexpected exception on closing JDBC Statement", ex);
            }
        }
    }

    /**
     * Close the given JDBC ResultSet and ignore any thrown exception.
     * This is useful for typical finally blocks in manual JDBC code.
     *
     * @param rs the JDBC ResultSet to close (may be {@code null})
     */
    public static void closeResultSet(@Nullable ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ex) {
                logger.trace("Could not close JDBC ResultSet", ex);
            } catch (Throwable ex) {
                // We don't trust the JDBC driver: It might throw RuntimeException or Error.
                logger.trace("Unexpected exception on closing JDBC ResultSet", ex);
            }
        }
    }


    /**
     * Extract database meta-data via the given DatabaseMetaDataCallback.
     * <p>This method will open a connection to the database and retrieve its meta-data.
     * Since this method is called before the exception translation feature is configured
     * for a DataSource, this method can not rely on SQLException translation itself.
     * <p>Any exceptions will be wrapped in a MetaDataAccessException. This is a checked
     * exception and any calling code should catch and handle this exception. You can just
     * log the error and hope for the best, but there is probably a more serious error that
     * will reappear when you try to access the database again.
     *
     * @param dataSource the DataSource to extract meta-data for
     * @param action     callback that will do the actual work
     * @return object containing the extracted information, as returned by
     * the DatabaseMetaDataCallback's {@code processMetaData} method
     * @see DatabaseMetaData
     */
    public static <T> T extractDatabaseMetaData(DataSource dataSource, DatabaseMetaDataCallback<T> action) {

        Connection con = null;
        boolean isTransactional = false;
        try {
            con = dataSource.getConnection();
            DatabaseMetaData metaData;
            try {
                metaData = con.getMetaData();
            } catch (SQLException ex) {
                logger.debug("Failed to obtain DatabaseMetaData from Connection - " +
                        "retrying against fresh Connection", ex);
                closeConnection(con);
                con = dataSource.getConnection();
                metaData = con.getMetaData();
            }
            if (metaData == null) {
                // should only happen in test environments
                throw new DbException("DatabaseMetaData returned by Connection [" + con + "] was null");
            }
            return action.processMetaData(metaData);
        } catch (SQLException ex) {
            throw new DbException("Error while extracting DatabaseMetaData", ex);
        } catch (AbstractMethodError err) {
            throw new DbException(
                    "JDBC DatabaseMetaData method not implemented by JDBC driver - upgrade your driver", err);
        } catch (Exception ex) {
            throw new DbException("Could not get Connection for extracting meta-data", ex);
        } finally {
            closeConnection(con);
        }
    }


    /**
     * Return whether the given JDBC driver supports JDBC 2.0 batch updates.
     * <p>Typically invoked right before execution of a given set of statements:
     * to decide whether the set of SQL statements should be executed through
     * the JDBC 2.0 batch mechanism or simply in a traditional one-by-one fashion.
     * <p>Logs a warning if the "supportsBatchUpdates" methods throws an exception
     * and simply returns {@code false} in that case.
     *
     * @param con the Connection to check
     * @return whether JDBC 2.0 batch updates are supported
     * @see DatabaseMetaData#supportsBatchUpdates()
     */
    public static boolean supportsBatchUpdates(Connection con) {
        try {
            DatabaseMetaData dbmd = con.getMetaData();
            if (dbmd != null) {
                if (dbmd.supportsBatchUpdates()) {
                    logger.debug("JDBC driver supports batch updates");
                    return true;
                } else {
                    logger.debug("JDBC driver does not support batch updates");
                }
            }
        } catch (SQLException ex) {
            logger.debug("JDBC driver 'supportsBatchUpdates' method threw exception", ex);
        } catch (AbstractMethodError err) {
            logger.debug("JDBC driver does not support JDBC 2.0 'supportsBatchUpdates' method - " +
                    "using internal batch", err);
        }
        return false;
    }


    /**
     * Return the database type based on connection meta data.
     *
     * @param con the Connection to check
     * @return the matched {@link DbType}
     */
    public static DbType getDatabaseType(Connection con) {
        try {
            DatabaseMetaData metaData = con.getMetaData();
            String databaseName = metaData.getDatabaseProductName().toLowerCase();
            return DbType.of(databaseName);
        } catch (SQLException e) {
            logger.warn("Exception thrown when getting database type", e);
        }
        return DbType.other;
    }

    /**
     * Determine the column name to use. The column name is determined based on a
     * lookup using ResultSetMetaData.
     * <p>This method implementation takes into account the recent JDBC difficulties
     * with column naming: in a JDBC 4.0-compliant environment the column label is
     * supplied, but in some JDBC 3.0 environments the column label is null.
     *
     * @param resultSetMetaData the current meta-data
     * @param columnIndex       the index of the column
     * @return the column name to use
     * @throws SQLException in case of lookup failure
     */
    public static String lookupColumnName(ResultSetMetaData resultSetMetaData, int columnIndex) throws SQLException {
        String name = resultSetMetaData.getColumnLabel(columnIndex);
        if (Strings.isEmpty(name)) {
            name = resultSetMetaData.getColumnName(columnIndex);
        }
        return name;
    }

    /**
     * Convert a column name with underscores to the corresponding property name following JavaBean conventions.
     *
     * @param name the column name to convert
     * @return the property name
     */
    public static String convertUnderscoreNameToPropertyName(@Nullable String name) {
        StringBuilder result = new StringBuilder();
        boolean nextIsUpper = false;
        if (name != null && name.length() > 0) {
            if (name.length() > 1 && name.substring(1, 2).equals("_")) {
                result.append(name.substring(0, 1).toUpperCase());
            } else {
                result.append(name.substring(0, 1).toLowerCase());
            }
            for (int i = 1; i < name.length(); i++) {
                String s = name.substring(i, i + 1);
                if (s.equals("_")) {
                    nextIsUpper = true;
                } else {
                    if (nextIsUpper) {
                        result.append(s.toUpperCase());
                        nextIsUpper = false;
                    } else {
                        result.append(s.toLowerCase());
                    }
                }
            }
        }
        return result.toString();
    }

}
