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
