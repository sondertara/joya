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

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlScriptSplitter;
import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 轻量级 SQL 脚本执行工具（供 {@code DataOps.script(Reader)} 逃生舱与初始化测试使用）。
 *
 * <p>执行前通过 {@link SqlScriptSplitter} 按方言词法一次性切分整份脚本（跳过引号字面量、注释与美元引号中的分号；
 * 词法未闭合错误在执行第 1 条语句前即快速失败），日志输出默认脱敏（不打印 SQL 明文与查询结果单元格明文）。
 */
public class ScriptRunner {

    private final Connection connection;
    private final @Nullable DbType dbType;
    private final @Nullable IntSupplier queryTimeoutSeconds;

    private boolean stopOnError;
    private boolean throwWarning;

    private PrintWriter logWriter;
    private PrintWriter errorLogWriter;

    public ScriptRunner(Connection connection) {
        this(connection, null);
    }

    public ScriptRunner(Connection connection, @Nullable DbType dbType) {
        this(connection, dbType, null);
    }

    /**
     * @param queryTimeoutSeconds 每次执行语句前重检剩余超时秒数（返回值 {@code <= 0} 表示不限时）
     */
    public ScriptRunner(Connection connection, @Nullable DbType dbType,
                        @Nullable IntSupplier queryTimeoutSeconds) {
        this.connection = connection;
        this.dbType = dbType;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
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
        final List<SqlScriptSplitter.Statement> statements;
        try {
            String script = readAll(reader);
            statements = SqlScriptSplitter.split(script, dbType);
        } catch (Exception e) {
            String message = "Error parsing SQL script: " + e.getMessage();
            printlnError(message);
            throw new DbException(message, e);
        }

        for (SqlScriptSplitter.Statement stmt : statements) {
            println(commandDescription(stmt.sql()));
            executeStatement(stmt);
        }
    }

    private static String readAll(Reader reader) throws IOException {
        if (reader == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1024);
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }

    private void executeStatement(SqlScriptSplitter.Statement stmt) {
        try (Statement statement = connection.createStatement()) {
            if (queryTimeoutSeconds != null) {
                int remaining = queryTimeoutSeconds.getAsInt();
                if (remaining > 0) {
                    statement.setQueryTimeout(remaining);
                }
            }
            boolean hasResults = statement.execute(stmt.sql());
            // 必须保留 getUpdateCount() == -1 终止判定以推进多结果集游标
            while (!(!hasResults && statement.getUpdateCount() == -1)) {
                checkWarnings(statement);
                printResults(statement, hasResults);
                hasResults = statement.getMoreResults();
            }
        } catch (SQLException e) {
            String message = "Error executing: " + commandDescription(stmt.sql())
                    + " (line " + stmt.line() + ").  Cause: " + e;
            printlnError(message);
            if (stopOnError || e instanceof SQLWarning) {
                throw new DbException(message, e);
            }
        } catch (Exception e) {
            String message = "Error executing: " + commandDescription(stmt.sql())
                    + " (line " + stmt.line() + ").  Cause: " + e;
            printlnError(message);
            throw new DbException(message, e);
        }
    }

    private void checkWarnings(Statement statement) throws SQLException {
        if (!throwWarning) {
            return;
        }
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
            if (rs == null) {
                return;
            }
            ResultSetMetaData md = rs.getMetaData();
            int cols = md != null ? md.getColumnCount() : 0;
            long rows = 0L;
            while (rs.next()) {
                rows++;
            }
            println("SQL script result[rows=" + rows + ", cols=" + cols + "]");
        } catch (SQLException e) {
            printlnError("Error reading script result summary: " + e.getMessage());
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
