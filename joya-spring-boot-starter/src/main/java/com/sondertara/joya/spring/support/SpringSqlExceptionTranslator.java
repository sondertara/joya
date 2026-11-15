package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Spring JDBC {@link SQLErrorCodeSQLExceptionTranslator} 适配：将驱动 {@link SQLException} 翻译为
 * Spring {@link DataAccessException} 体系（如 {@code DuplicateKeyException}、{@code BadSqlGrammarException} 等），
 * 同时保留原始 {@link SQLException} 作为 cause，兼容显式事务死锁重试策略。
 * <p>
 * 延迟至首次异常翻译时再基于 {@link DataSource} 探测错误码表，避免容器启动期提前获取 JDBC 连接。
 */
public final class SpringSqlExceptionTranslator implements SqlExceptionTranslator {

    @Nullable
    private final DataSource dataSource;
    private volatile org.springframework.jdbc.support.SQLExceptionTranslator delegate;

    public SpringSqlExceptionTranslator() {
        this(null);
    }

    public SpringSqlExceptionTranslator(@Nullable DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public RuntimeException translate(String task, @Nullable SqlStatement statement, SQLException ex) {
        String sql = statement != null ? statement.sql() : null;
        DataAccessException translated = delegate().translate(task, sql, ex);
        return translated != null ? translated : SqlExceptionTranslator.DEFAULT.translate(task, statement, ex);
    }

    private org.springframework.jdbc.support.SQLExceptionTranslator delegate() {
        org.springframework.jdbc.support.SQLExceptionTranslator current = this.delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = this.delegate;
            if (current != null) {
                return current;
            }
            if (dataSource == null) {
                current = new SQLExceptionSubclassTranslator();
            } else {
                try {
                    current = new SQLErrorCodeSQLExceptionTranslator(dataSource);
                } catch (RuntimeException ignored) {
                    current = new SQLExceptionSubclassTranslator();
                }
            }
            this.delegate = current;
            return current;
        }
    }
}
