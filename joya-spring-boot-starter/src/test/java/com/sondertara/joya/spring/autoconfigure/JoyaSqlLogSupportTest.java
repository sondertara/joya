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
}
