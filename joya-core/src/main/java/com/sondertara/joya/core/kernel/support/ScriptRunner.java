/*
 *    Copyright 2009-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.sondertara.joya.core.kernel.support;

import com.sondertara.joya.exceptions.DbException;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is an internal testing utility.<br>
 * You are welcome to use this class for your own purposes,<br>
 * but if there is some feature/enhancement you need for your own usage,<br>
 * please make and modify your own copy instead of sending us an enhancement request.<br>
 *
 * @author Clinton Begin
 */
public class ScriptRunner {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final String DEFAULT_DELIMITER = ";";

    private static final Pattern DELIMITER_PATTERN = Pattern
            .compile("^\\s*((--)|(//))?\\s*(//)?\\s*@DELIMITER\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE);

    private final Connection connection;

    private boolean stopOnError;
    private boolean throwWarning;

    private PrintWriter logWriter = new PrintWriter(System.out);
    private PrintWriter errorLogWriter = new PrintWriter(System.err);

    private String delimiter = DEFAULT_DELIMITER;

    public ScriptRunner(Connection connection) {
        this.connection = connection;
    }

    public void setStopOnError(boolean stopOnError) {
        this.stopOnError = stopOnError;
    }

    public void setThrowWarning(boolean throwWarning) {
        this.throwWarning = throwWarning;
    }






    public void setLogWriter(PrintWriter logWriter) {
        this.logWriter = logWriter;
    }

    public void setErrorLogWriter(PrintWriter errorLogWriter) {
        this.errorLogWriter = errorLogWriter;
    }



    public void runScript(Reader reader) {
        executeLineByLine(reader);
    }


    private void executeLineByLine(Reader reader) {
        StringBuilder command = new StringBuilder();
        try {
            BufferedReader lineReader = new BufferedReader(reader);
            String line;
            while ((line = lineReader.readLine()) != null) {
                handleLine(command, line);
            }
            checkForMissingLineTerminator(command);
        } catch (Exception e) {
            String message = "Error executing: " + commandDescription(command) + ".  Cause: " + e;
            printlnError(message);
            throw new DbException(message, e);
        }
    }

    private void checkForMissingLineTerminator(StringBuilder command) {
        if (command != null && !command.toString().trim().isEmpty()) {
            throw new DbException("Line missing end-of-line terminator (" + delimiter + ") => "
                    + commandDescription(command));
        }
    }

    private void handleLine(StringBuilder command, String line) throws SQLException {
        String trimmedLine = line.trim();
        if (lineIsComment(trimmedLine)) {
            Matcher matcher = DELIMITER_PATTERN.matcher(trimmedLine);
            if (matcher.find()) {
                delimiter = matcher.group(5);
            }
            println("SQL script comment");
        } else if (commandReadyToExecute(trimmedLine)) {
            command.append(line, 0, line.lastIndexOf(delimiter));
            command.append(LINE_SEPARATOR);
            println(commandDescription(command));
            executeStatement(command.toString());
            command.setLength(0);
        } else if (!trimmedLine.isEmpty()) {
            command.append(line);
            command.append(LINE_SEPARATOR);
        }
    }

    private boolean lineIsComment(String trimmedLine) {
        return trimmedLine.startsWith("//") || trimmedLine.startsWith("--");
    }

    private boolean commandReadyToExecute(String trimmedLine) {
        // issue #561 remove anything after the delimiter
        return trimmedLine.contains(delimiter);
    }

    private void executeStatement(String command) throws SQLException {
        try (Statement statement = connection.createStatement()) {
                try {
                boolean hasResults = statement.execute(command);
                // DO NOT try to 'imporove' the condition even if IDE tells you to!
                // It's important that getUpdateCount() is called here.
                while (!(!hasResults && statement.getUpdateCount() == -1)) {
                    checkWarnings(statement);
                    printResults(statement, hasResults);
                    hasResults = statement.getMoreResults();
                }
            } catch (SQLWarning e) {
                throw e;
            } catch (SQLException e) {
                if (stopOnError) {
                    throw e;
                }
                String message = "Error executing: " + commandDescription(command) + ".  Cause: " + e;
                printlnError(message);
            }
        }
    }

    private void checkWarnings(Statement statement) throws SQLException {
        if (!throwWarning) {
            return;
        }
        // In Oracle, CREATE PROCEDURE, FUNCTION, etc. returns warning
        // instead of throwing exception if there is compilation error.
        SQLWarning warning = statement.getWarnings();
        if (warning != null) {
            throw warning;
        }
    }


    private String commandDescription(CharSequence command) {
        String sql = command.toString().trim();
        int keywordEnd = 0;
        while (keywordEnd < sql.length() && !Character.isWhitespace(sql.charAt(keywordEnd))) {
            keywordEnd++;
        }
        String keyword = keywordEnd == 0 ? "EMPTY" : sql.substring(0, keywordEnd).toUpperCase(java.util.Locale.ROOT);
        String type = switch (keyword) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE",
                    "MERGE", "CALL", "WITH", "BEGIN", "COMMIT", "ROLLBACK", "GRANT", "REVOKE" -> keyword;
            case "EMPTY" -> "EMPTY";
            default -> "OTHER";
        };
        return "SQL script statement[type=" + type
                + ", length=" + sql.length()
                + ", fingerprint=" + Integer.toHexString(sql.hashCode()) + "]";
    }

    private void printResults(Statement statement, boolean hasResults) {
        if (!hasResults) {
            return;
        }
        try (ResultSet rs = statement.getResultSet()) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            for (int i = 0; i < cols; i++) {
                String name = md.getColumnLabel(i + 1);
                print(name + "\t");
            }
            println("");
            while (rs.next()) {
                for (int i = 0; i < cols; i++) {
                    String value = rs.getString(i + 1);
                    print(value + "\t");
                }
                println("");
            }
        } catch (SQLException e) {
            printlnError("Error printing results: " + e.getMessage());
        }
    }

    private void print(Object o) {
        if (logWriter != null) {
            logWriter.print(o);
            logWriter.flush();
        }
    }

    private void println(Object o) {
        if (logWriter != null) {
            logWriter.println(o);
            logWriter.flush();
        }
    }

    private void printlnError(Object o) {
        if (errorLogWriter != null) {
            errorLogWriter.println(o);
            errorLogWriter.flush();
        }
    }

}