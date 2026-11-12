package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.support.ScriptRunner;
import com.sondertara.joya.exceptions.DbException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlScriptSplitter} 与 {@link ScriptRunner} 词法切分、前置快速失败及结果集日志脱敏测试。
 */
class SqlScriptSplitterTest {

    @Test
    void split_handlesMultilineLiteralsCommentsSingleLineMultipleStatementsAndBom() {
        String script = "\uFEFF"
                + "/* 索引说明; 含分号 */\n"
                + "CREATE TABLE t_split (\n"
                + "    id BIGINT PRIMARY KEY, -- 主键; 说明\n"
                + "    body VARCHAR(128)\n"
                + "); -- 建表完成;\n"
                + "INSERT INTO t_split VALUES (1, 'part1;\npart2');\n"
                + "UPDATE t_split SET body = 'x' WHERE id = 1; UPDATE t_split SET body = 'y' WHERE id = 2;\n"
                + "// 脚本级注释; 不送入 JDBC\n";

        List<SqlScriptSplitter.Statement> stmts = SqlScriptSplitter.split(script, DbType.h2);
        assertEquals(4, stmts.size());
        assertEquals(2, stmts.get(0).line());
        assertTrue(stmts.get(0).sql().startsWith("CREATE TABLE t_split"));
        assertEquals(6, stmts.get(1).line());
        assertEquals("INSERT INTO t_split VALUES (1, 'part1;\npart2')", stmts.get(1).sql());
        assertEquals(8, stmts.get(2).line());
        assertEquals("UPDATE t_split SET body = 'x' WHERE id = 1", stmts.get(2).sql());
        assertEquals(8, stmts.get(3).line());
        assertEquals("UPDATE t_split SET body = 'y' WHERE id = 2", stmts.get(3).sql());
    }

    @Test
    void split_respectsPostgresDollarQuotingNestedBlockCommentsSqlServerBracketsAndMysqlEscapes() {
        // 1. PostgreSQL 美元引号与嵌套块注释
        String pgScript = """
                /* 外层注释; /* 内层嵌套; */ 结束 */
                CREATE FUNCTION demo_fn() RETURNS void AS $body$
                BEGIN
                    PERFORM 1;
                    PERFORM 2;
                END;
                $body$ LANGUAGE plpgsql;
                SELECT 1;
                """;
        List<SqlScriptSplitter.Statement> pgStmts = SqlScriptSplitter.split(pgScript, DbType.postgresql);
        assertEquals(2, pgStmts.size());
        assertEquals(2, pgStmts.get(0).line());
        assertTrue(pgStmts.get(0).sql().contains("PERFORM 1;"));
        assertEquals(8, pgStmts.get(1).line());
        assertEquals("SELECT 1", pgStmts.get(1).sql());

        // 2. SQL Server 方括号标识符含分号及 #temp 临时表
        String msScript = """
                SELECT [col;a], [col]]b;c] FROM #temp_semicolon;
                """;
        List<SqlScriptSplitter.Statement> msStmts = SqlScriptSplitter.split(msScript, DbType.sqlserver);
        assertEquals(1, msStmts.size());
        assertEquals("SELECT [col;a], [col]]b;c] FROM #temp_semicolon", msStmts.get(0).sql());

        // 3. MySQL 反斜杠转义引号与 # 行注释
        String mysqlScript = """
                # 注释; 分号
                INSERT INTO t VALUES ('escaped \\'; still inside');
                """;
        List<SqlScriptSplitter.Statement> mysqlStmts = SqlScriptSplitter.split(mysqlScript, DbType.mysql);
        assertEquals(1, mysqlStmts.size());
        assertEquals(2, mysqlStmts.get(0).line());
    }

    @Test
    void split_supportsDelimiterDirectiveSwitchAndRestore() {
        String script = """
                -- @DELIMITER $$
                CREATE PROCEDURE sp_demo()
                BEGIN
                    INSERT INTO t VALUES (1, 'a;b');
                    INSERT INTO t VALUES (2, 'c');
                END $$
                -- @DELIMITER ;
                INSERT INTO t VALUES (3, 'after');
                """;
        List<SqlScriptSplitter.Statement> stmts = SqlScriptSplitter.split(script, DbType.mysql);
        assertEquals(2, stmts.size());
        assertEquals(2, stmts.get(0).line());
        assertTrue(stmts.get(0).sql().contains("INSERT INTO t VALUES (1, 'a;b');"));
        assertEquals(8, stmts.get(1).line());
        assertEquals("INSERT INTO t VALUES (3, 'after')", stmts.get(1).sql());
    }

    @Test
    void split_rejectsUnterminatedTokensAndMissingTerminatorWithAccurateLineAndRedactedMessage() {
        String secret = "SENSITIVE_TOKEN_9981";

        IllegalArgumentException unclosedQuote = assertThrows(IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("SELECT 1;\nSELECT 2;\nINSERT INTO t VALUES ('" + secret + "\n", DbType.h2));
        assertTrue(unclosedQuote.getMessage().contains("line 3"), unclosedQuote.getMessage());
        assertFalse(unclosedQuote.getMessage().contains(secret), "异常不得回显未闭合字面量明文");

        IllegalArgumentException unclosedComment = assertThrows(IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("SELECT 1;\n/* unclosed comment " + secret, DbType.h2));
        assertTrue(unclosedComment.getMessage().contains("line 2"), unclosedComment.getMessage());
        assertFalse(unclosedComment.getMessage().contains(secret));

        IllegalArgumentException unclosedDollar = assertThrows(IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("SELECT 1;\n\n\nSELECT $$" + secret, DbType.postgresql));
        assertTrue(unclosedDollar.getMessage().contains("line 4"), unclosedDollar.getMessage());
        assertFalse(unclosedDollar.getMessage().contains(secret));

        IllegalArgumentException missingTerminator = assertThrows(IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("SELECT 1;\nDELETE FROM t_critical WHERE secret = '" + secret + "'", DbType.h2));
        assertTrue(missingTerminator.getMessage().contains("line 2"), missingTerminator.getMessage());
        assertFalse(missingTerminator.getMessage().contains(secret));
    }

    @Test
    void scriptRunner_executesMixedScript_failsBeforeAnyExecutionOnLexicalError_andRedactsSelectCells() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:script_splitter_it;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        List<String> listenerEvents = new ArrayList<>();
        JoyaClient joya = JoyaClient.builder(ds)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext context) {
                        listenerEvents.add(context.statement().sql());
                    }
                })
                .build();

        // 1. 词法错误前置拦截：第 1 条合法建表语句绝不被执行
        String brokenScript = """
                CREATE TABLE t_should_not_exist (id BIGINT PRIMARY KEY);
                INSERT INTO t_should_not_exist VALUES (1, 'unterminated;
                """;
        assertThrows(DbException.class, () -> joya.script(new StringReader(brokenScript)));
        Long tableCount = joya.sql(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'T_SHOULD_NOT_EXIST'")
                .value(Long.class).orElseThrow();
        assertEquals(0L, tableCount, "先整体切分再执行：后段词法错误必须阻止前段任何语句落库");

        // 2. 混合注释分号 + 跨行分号字符串端到端建表与写入，且逃生舱不触发 SqlExecutionListener
        listenerEvents.clear();
        String validScript = """
                /* 表说明; 含分号 */
                CREATE TABLE t_script_ok (id BIGINT PRIMARY KEY, payload VARCHAR(100)); -- 尾注;
                INSERT INTO t_script_ok VALUES (1, 'line1;
                line2');
                INSERT INTO t_script_ok VALUES (2, 'alpha;beta'); INSERT INTO t_script_ok VALUES (3, 'gamma');
                """;
        joya.script(new StringReader(validScript));
        assertEquals(List.of(), listenerEvents, "DataOps.script 作为逃生舱不得触发 SqlExecutionListener");
        assertEquals("line1;\nline2",
                joya.sql("SELECT payload FROM t_script_ok WHERE id = 1").value(String.class).orElseThrow());
        assertEquals(3L, joya.sql("SELECT COUNT(*) FROM t_script_ok").value(Long.class).orElseThrow());

        // 3. ScriptRunner DEBUG 日志只输出 rows/cols 摘要，绝不泄露单元格与列名明文
        String sensitiveCell = "SUPER_SECRET_CELL_VALUE_42";
        joya.sql("INSERT INTO t_script_ok VALUES (99, ?)", sensitiveCell).update();
        StringWriter debugOut = new StringWriter();
        try (Connection conn = ds.getConnection()) {
            ScriptRunner runner = new ScriptRunner(conn, DbType.h2);
            runner.setLogWriter(new PrintWriter(debugOut));
            runner.runScript(new StringReader("SELECT id, payload AS secret_col_alias FROM t_script_ok;"));
        }
        String logContent = debugOut.toString();
        assertFalse(logContent.contains(sensitiveCell), "结果集日志不得打印任何单元格明文: " + logContent);
        assertFalse(logContent.contains("secret_col_alias"), "结果集日志不得打印列标签明细: " + logContent);
        assertTrue(logContent.contains("rows=4, cols=2"), "结果集日志应保留行列数摘要: " + logContent);
    }

    @Test
    void split_treatsDoubleDashAsCommentOnlyWithWhitespaceOnMysqlAndGenericDialects() {
        // MySQL 家族：x--1 是“减负一”的算术，不能被吞成行注释。
        List<SqlScriptSplitter.Statement> mysql = SqlScriptSplitter.split("SELECT x--1 FROM t;", DbType.mysql);
        assertEquals(1, mysql.size());
        assertEquals("SELECT x--1 FROM t", mysql.get(0).sql());

        // 方言未知（通用模式）沿用保守规则，避免初始化脚本在 MySQL 上被执行器误判。
        assertEquals(1, SqlScriptSplitter.split("SELECT x--1 FROM t;", null).size());

        // MySQL 下 -- 后带空白仍是行注释，注释内的分号不参与切分。
        List<SqlScriptSplitter.Statement> commented = SqlScriptSplitter.split(
                "UPDATE t SET x = 1; -- 注释里含分号;\nSELECT 2;", DbType.mysql);
        assertEquals(2, commented.size());

        // 标准方言（PostgreSQL 等）任意位置 -- 都是注释：x--1 后的分号被注释吞掉 → 缺少终止符快速失败，且不回显原文。
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlScriptSplitter.split("SELECT x--1 FROM t;", DbType.postgresql));
        assertTrue(e.getMessage().contains("missing end-of-statement terminator"));
        assertFalse(e.getMessage().contains("x--1"));
    }

    @Test
    void split_acceptsDelimiterDirectiveWithTrailingComment() {
        String script = "@DELIMITER $$ -- 切换结束符\n"
                + "CREATE PROCEDURE demo() BEGIN SELECT 1; END$$\n"
                + "@DELIMITER ; -- 恢复默认\n"
                + "SELECT 2;";

        List<SqlScriptSplitter.Statement> stmts = SqlScriptSplitter.split(script, DbType.h2);
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).sql().startsWith("CREATE PROCEDURE demo()"));
        assertTrue(stmts.get(0).sql().endsWith("END"));
        assertEquals("SELECT 2", stmts.get(1).sql());
    }
}
