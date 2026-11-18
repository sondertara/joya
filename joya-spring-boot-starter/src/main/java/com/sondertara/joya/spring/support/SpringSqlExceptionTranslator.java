package com.sondertara.joya.spring.support;

import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodesFactory;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Spring JDBC {@link org.springframework.jdbc.support.SQLExceptionTranslator} 适配：将驱动 {@link SQLException} 翻译为
 * Spring {@link DataAccessException} 体系（如 {@code DuplicateKeyException}、{@code BadSqlGrammarException} 等），
 * 同时保留原始 {@link SQLException} 作为 cause，兼容显式事务死锁重试策略。
 *
 * <p>对齐 Spring Framework 6 {@link org.springframework.jdbc.support.JdbcAccessor#getExceptionTranslator()} 默认策略：
 * 默认采用零连接开销的 {@link SQLExceptionSubclassTranslator}（基于 JDBC 4 异常子类与 SQLState 翻译，
 * 避免连接池耗尽或断连故障期在同步锁内二次获取连接阻塞及固化空错误码）；仅当存在用户自定义
 * {@code sql-error-codes.xml} 时才惰性启用 {@link SQLErrorCodeSQLExceptionTranslator}。
 */
public final class SpringSqlExceptionTranslator implements SqlExceptionTranslator {

    // SQLErrorCodeSQLExceptionTranslator.hasUserProvidedErrorCodesFile() 为包级私有方法，跨包复用公开常量单次探测
    private static final boolean USER_ERROR_CODES_PRESENT = new ClassPathResource(
            SQLErrorCodesFactory.SQL_ERROR_CODE_OVERRIDE_PATH,
            SQLErrorCodeSQLExceptionTranslator.class.getClassLoader()).exists();

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
            if (dataSource != null && USER_ERROR_CODES_PRESENT) {
                current = new SQLErrorCodeSQLExceptionTranslator(dataSource);
            } else {
                current = new SQLExceptionSubclassTranslator();
            }
            this.delegate = current;
            return current;
        }
    }
}
