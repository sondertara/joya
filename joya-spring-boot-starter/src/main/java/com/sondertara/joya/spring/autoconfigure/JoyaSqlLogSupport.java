package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * SQL 明文日志的参数脱敏支撑：按参数逻辑名（列名/条件名）匹配脱敏正则，命中后把值替换为 {@code ***}。
 */
final class JoyaSqlLogSupport {

    /** 脱敏占位。 */
    static final String MASK = "***";

    private JoyaSqlLogSupport() {
    }

    /**
     * 编译脱敏正则；任一模式非法时在启动期立即失败（Fail-Fast），并带出配置项下标。
     */
    static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        List<Pattern> compiled = new ArrayList<>(patterns.size());
        for (int i = 0; i < patterns.size(); i++) {
            String regex = patterns.get(i);
            if (regex == null || regex.isBlank()) {
                throw new IllegalArgumentException("joya.sql-log-redact-patterns[" + i + "] must not be blank");
            }
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException(
                        "joya.sql-log-redact-patterns[" + i + "] is not a valid regex: " + regex, ex);
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * 构造内置 SQL 执行日志监听器：
     * <ul>
     *   <li>开启 {@code sqlLog=true} 时输出所有语句的 {@code INFO} 执行日志与 {@code ERROR} 异常日志；</li>
     *   <li>配置 {@code slowSqlThresholdMs > 0} 时，无论是否开启全量 {@code sqlLog}，达到慢阈值的语句均以 {@code WARN} 告警（慢异常以 {@code ERROR} 输出）。</li>
     * </ul>
     */
    static com.sondertara.joya.core.kernel.observe.SqlExecutionListener createListener(
            org.slf4j.Logger log,
            JoyaProperties properties) {
        List<Pattern> redactPatterns = compilePatterns(properties.getSqlLogRedactPatterns());
        long thresholdMs = properties.getSlowSqlThresholdMs();
        long thresholdNanos = thresholdMs > 0L
                ? java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(thresholdMs)
                : 0L;
        boolean sqlLog = properties.isSqlLog();
        boolean printArgs = properties.isSqlLogPrintArgs();

        return new com.sondertara.joya.core.kernel.observe.SqlExecutionListener() {
            @Override
            public void afterExecute(com.sondertara.joya.core.kernel.observe.SqlExecutionContext context,
                                     com.sondertara.joya.core.kernel.observe.SqlExecutionResult result) {
                long elapsedNanos = Math.max(0L, result.elapsedNanos());
                boolean slow = thresholdNanos > 0L && elapsedNanos >= thresholdNanos;
                if (slow) {
                    if (!log.isWarnEnabled()) {
                        return;
                    }
                } else if (!sqlLog || !log.isInfoEnabled()) {
                    return;
                }
                String elapsedMs = formatElapsedMs(elapsedNanos);
                String details = formatExecutionDetails(context, result, printArgs, redactPatterns);
                if (slow) {
                    log.warn("[Joya SQL SLOW] {} | elapsed={}ms (>={}ms) | {}",
                            context.statement().sql(), elapsedMs, thresholdMs, details);
                } else {
                    log.info("[Joya SQL] {} | elapsed={}ms | {}",
                            context.statement().sql(), elapsedMs, details);
                }
            }

            @Override
            public void onError(com.sondertara.joya.core.kernel.observe.SqlExecutionContext context,
                                Throwable error,
                                long elapsedNanos) {
                long safeElapsed = Math.max(0L, elapsedNanos);
                boolean slow = thresholdNanos > 0L && safeElapsed >= thresholdNanos;
                if ((!sqlLog && !slow) || !log.isErrorEnabled()) {
                    return;
                }
                String elapsedMs = formatElapsedMs(safeElapsed);
                String details = formatExecutionDetails(context, null, printArgs, redactPatterns);
                String errorSummary = error == null
                        ? "unknown"
                        : error.getClass().getSimpleName() + ": " + error.getMessage();
                log.error("[Joya SQL ERROR] {} | elapsed={}ms | {} | error={}",
                        context.statement().sql(), elapsedMs, details, errorSummary);
            }
        };
    }

    private static String formatElapsedMs(long elapsedNanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", elapsedNanos / 1_000_000.0d);
    }

    private static String formatExecutionDetails(
            com.sondertara.joya.core.kernel.observe.SqlExecutionContext context,
            com.sondertara.joya.core.kernel.observe.SqlExecutionResult result,
            boolean printArgs,
            List<Pattern> redactPatterns) {
        StringBuilder sb = new StringBuilder();
        if (context.batch()) {
            sb.append("batch rows=").append(
                    context.attributes().get(com.sondertara.joya.core.kernel.observe.SqlExecutionContext.ATTR_BATCH_ROWS));
            if (result != null && result.batchCounts().length > 0) {
                long totalAffected = 0L;
                boolean hasKnown = false;
                for (int count : result.batchCounts()) {
                    if (count >= 0) {
                        totalAffected += count;
                        hasKnown = true;
                    }
                }
                if (hasKnown) {
                    sb.append(", affected=").append(totalAffected);
                }
            }
        } else {
            if (printArgs) {
                sb.append("args=").append(maskedArgs(context.statement(), redactPatterns));
            } else {
                sb.append("paramCount=").append(context.statement().args().size());
            }
            if (result != null) {
                if (result.rowCount() >= 0) {
                    sb.append(" | rows=").append(result.rowCount());
                } else if (result.affectedRows() >= 0) {
                    sb.append(" | affected=").append(result.affectedRows());
                }
            }
        }
        if (context.inPage()) {
            sb.append(" | page=[").append(context.offset()).append(',').append(context.limit()).append(']');
        }
        return sb.toString();
    }

    /**
     * 生成可安全打印的参数值列表；未配置模式时直接复用原始值（零拷贝语义）。
     */
    static List<Object> maskedArgs(SqlStatement statement, List<Pattern> patterns) {
        List<SqlParameter> parameters = statement.parameters();
        if (patterns.isEmpty()) {
            return statement.args();
        }
        List<Object> masked = new ArrayList<>(parameters.size());
        for (SqlParameter parameter : parameters) {
            masked.add(isSensitive(parameter.name(), patterns) ? MASK : parameter.value());
        }
        return masked;
    }

    private static boolean isSensitive(String name, List<Pattern> patterns) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }
}
