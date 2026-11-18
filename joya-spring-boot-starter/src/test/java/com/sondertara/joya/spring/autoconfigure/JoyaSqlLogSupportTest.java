package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * E-4 第二名：开启明文参数日志时，敏感列默认脱敏；模式可配置且非法正则启动期失败。
 */
class JoyaSqlLogSupportTest {

    private static SqlStatement statement(SqlParameter... parameters) {
        return SqlStatement.of("insert into t_user (password, user_name) values (?, ?)",
                List.of(parameters), StatementMetadata.empty());
    }

    @Test
    void defaultPatternsMaskSensitiveColumns() {
        List<Pattern> patterns = JoyaSqlLogSupport.compilePatterns(
                new JoyaProperties().getSqlLogRedactPatterns());

        List<Object> args = JoyaSqlLogSupport.maskedArgs(statement(
                SqlParameter.of(1, "password", "p@ss", ParameterSource.INSERT),
                SqlParameter.of(2, "user_name", "tom", ParameterSource.INSERT)), patterns);

        assertEquals(List.of("***", "tom"), args);
    }

    @Test
    void customPatternOverridesNothingButAdds() {
        List<Pattern> patterns = JoyaSqlLogSupport.compilePatterns(List.of("(?i).*id_card.*"));

        List<Object> args = JoyaSqlLogSupport.maskedArgs(statement(
                SqlParameter.of(1, "id_card", "110101", ParameterSource.INSERT),
                SqlParameter.of(2, "password", "p@ss", ParameterSource.INSERT)), patterns);

        assertEquals(List.of("***", "p@ss"), args, "自定义模式只应额外生效，不做隐式兜底");
    }

    @Test
    void emptyPatternsKeepOriginalValues() {
        List<Object> args = JoyaSqlLogSupport.maskedArgs(statement(
                SqlParameter.of(1, "password", "p@ss", ParameterSource.INSERT)), List.of());

        assertEquals(List.of("p@ss"), args);
    }

    @Test
    void invalidOrBlankRegexFailsFastWithConfigurationIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> JoyaSqlLogSupport.compilePatterns(List.of("(", "(?i).*token.*")));
        assertThrows(IllegalArgumentException.class,
                () -> JoyaSqlLogSupport.compilePatterns(List.of(" ")));
    }

    @Test
    void createListener_logsElapsedTimeSlowWarnAndErrorsWithRedaction() {
        List<String> logEntries = new java.util.ArrayList<>();
        org.slf4j.Logger captureLog = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
                org.slf4j.Logger.class.getClassLoader(),
                new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("isInfoEnabled".equals(name) || "isWarnEnabled".equals(name) || "isErrorEnabled".equals(name)) {
                        return true;
                    }
                    if ("info".equals(name) || "warn".equals(name) || "error".equals(name)) {
                        StringBuilder line = new StringBuilder(name.toUpperCase(java.util.Locale.ROOT)).append(":");
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Object[] varargs) {
                                    line.append(java.util.Arrays.toString(varargs));
                                } else {
                                    line.append(arg).append('|');
                                }
                            }
                        }
                        logEntries.add(line.toString());
                        return null;
                    }
                    return null;
                });

        JoyaProperties props = new JoyaProperties();
        props.setSqlLog(true);
        props.setSqlLogPrintArgs(true);
        props.setSlowSqlThresholdMs(50L);

        com.sondertara.joya.core.kernel.observe.SqlExecutionListener listener =
                JoyaSqlLogSupport.createListener(captureLog, props);
        com.sondertara.joya.core.kernel.statement.SqlStatement stmt = statement(
                SqlParameter.of(1, "password", "secret123", ParameterSource.INSERT),
                SqlParameter.of(2, "userName", "alice", ParameterSource.INSERT));
        com.sondertara.joya.core.kernel.observe.SqlExecutionContext ctx =
                new com.sondertara.joya.core.kernel.observe.SqlExecutionContext(stmt, false);

        // 1. 正常耗时 -> INFO（密码参数脱敏，显示 rows 与 elapsedMs）
        listener.afterExecute(ctx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(2L).withElapsedNanos(5_000_000L));
        org.junit.jupiter.api.Assertions.assertEquals(1, logEntries.size());
        String normalLog = logEntries.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(normalLog.startsWith("INFO:[Joya SQL]"), normalLog);
        org.junit.jupiter.api.Assertions.assertTrue(normalLog.contains("5.00"), normalLog);
        org.junit.jupiter.api.Assertions.assertTrue(normalLog.contains("***") && normalLog.contains("alice"), normalLog);
        org.junit.jupiter.api.Assertions.assertTrue(normalLog.contains("rows=2"), normalLog);

        // 2. 达到慢 SQL 阈值 -> WARN
        logEntries.clear();
        listener.afterExecute(ctx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.affected(1L).withElapsedNanos(60_000_000L));
        String slowLog = logEntries.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(slowLog.startsWith("WARN:[Joya SQL SLOW]"), slowLog);
        org.junit.jupiter.api.Assertions.assertTrue(slowLog.contains("60.00") && slowLog.contains("50"), slowLog);
        org.junit.jupiter.api.Assertions.assertTrue(slowLog.contains("affected=1"), slowLog);

        // 3. 异常 -> ERROR（保留耗时与异常信息）
        logEntries.clear();
        listener.onError(ctx, new IllegalStateException("boom"), 12_340_000L);
        String errorLog = logEntries.get(0);
        org.junit.jupiter.api.Assertions.assertTrue(errorLog.startsWith("ERROR:[Joya SQL ERROR]"), errorLog);
        org.junit.jupiter.api.Assertions.assertTrue(errorLog.contains("12.34") && errorLog.contains("IllegalStateException: boom"),
                errorLog);

        // 4. 生产慢查询独立模式（sqlLog=false, slowSqlThresholdMs=50）：快查询与快错误零输出，仅慢查询/慢异常输出
        JoyaProperties slowOnlyProps = new JoyaProperties();
        slowOnlyProps.setSqlLog(false);
        slowOnlyProps.setSlowSqlThresholdMs(50L);
        com.sondertara.joya.core.kernel.observe.SqlExecutionListener slowOnlyListener =
                JoyaSqlLogSupport.createListener(captureLog, slowOnlyProps);

        logEntries.clear();
        slowOnlyListener.afterExecute(ctx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(1L).withElapsedNanos(5_000_000L));
        slowOnlyListener.onError(ctx, new IllegalStateException("fast-fail"), 5_000_000L);
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.isEmpty(), "仅开启慢 SQL 阈值时快请求不得产生日志");

        slowOnlyListener.afterExecute(ctx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(1L).withElapsedNanos(55_000_000L));
        slowOnlyListener.onError(ctx, new IllegalStateException("slow-timeout"), 65_000_000L);
        org.junit.jupiter.api.Assertions.assertEquals(2, logEntries.size());
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(0).startsWith("WARN:[Joya SQL SLOW]"));
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(1).startsWith("ERROR:[Joya SQL ERROR]"));
    }

    @Test
    void createListener_formatsClientNameTagAndBatchRows() {
        List<String> logEntries = new java.util.ArrayList<>();
        org.slf4j.Logger captureLog = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
                org.slf4j.Logger.class.getClassLoader(),
                new Class<?>[]{org.slf4j.Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("isInfoEnabled".equals(name) || "isWarnEnabled".equals(name) || "isErrorEnabled".equals(name)) {
                        return true;
                    }
                    if (("info".equals(name) || "warn".equals(name) || "error".equals(name))
                            && args != null && args.length >= 2 && args[0] instanceof String fmt
                            && args[1] instanceof Object[] params) {
                        String rendered = fmt;
                        for (Object param : params) {
                            int idx = rendered.indexOf("{}");
                            if (idx >= 0) {
                                rendered = rendered.substring(0, idx) + param + rendered.substring(idx + 2);
                            }
                        }
                        logEntries.add(name.toUpperCase(java.util.Locale.ROOT) + ":" + rendered);
                    }
                    return null;
                });

        JoyaProperties props = new JoyaProperties();
        props.setSqlLog(true);
        props.setSlowSqlThresholdMs(50L);
        com.sondertara.joya.core.kernel.observe.SqlExecutionListener listener =
                JoyaSqlLogSupport.createListener(captureLog, props);

        SqlStatement stmt = SqlStatement.select("SELECT 1");
        var singleOrderCtx = new com.sondertara.joya.core.kernel.observe.SqlExecutionContext(
                stmt, false, -1, "order");
        var batchPrimaryCtx = new com.sondertara.joya.core.kernel.observe.SqlExecutionContext(
                stmt, true, 3, "primary");

        listener.afterExecute(singleOrderCtx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(1L).withElapsedNanos(2_000_000L));
        listener.afterExecute(singleOrderCtx,
                com.sondertara.joya.core.kernel.observe.SqlExecutionResult.rows(1L).withElapsedNanos(80_000_000L));
        listener.onError(singleOrderCtx, new IllegalStateException("fail"), 3_000_000L);
        listener.afterExecute(batchPrimaryCtx,
                new com.sondertara.joya.core.kernel.observe.SqlExecutionResult(-1L, -1L, new int[]{1, 1, 1}, List.of(), 4_000_000L));

        org.junit.jupiter.api.Assertions.assertEquals(4, logEntries.size());
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(0).startsWith("INFO:[Joya SQL][order] SELECT 1 |"), logEntries.get(0));
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(1).startsWith("WARN:[Joya SQL SLOW][order] SELECT 1 |"), logEntries.get(1));
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(2).startsWith("ERROR:[Joya SQL ERROR][order] SELECT 1 |"), logEntries.get(2));
        org.junit.jupiter.api.Assertions.assertTrue(
                logEntries.get(3).startsWith("INFO:[Joya SQL][primary] SELECT 1 |")
                        && logEntries.get(3).contains("batch rows=3, affected=3"),
                logEntries.get(3));

        // 便捷二参构造 batch=true 时未知行数统一以 -1 哨兵表达，日志输出 batch rows=?
        var unknownRowsBatchCtx = new com.sondertara.joya.core.kernel.observe.SqlExecutionContext(stmt, true);
        assertEquals(-1, unknownRowsBatchCtx.batchRows());
        logEntries.clear();
        listener.afterExecute(unknownRowsBatchCtx,
                new com.sondertara.joya.core.kernel.observe.SqlExecutionResult(-1L, -1L, new int[0], List.of(), 1_000_000L));
        org.junit.jupiter.api.Assertions.assertTrue(logEntries.get(0).contains("batch rows=?"), logEntries.get(0));
    }
}
