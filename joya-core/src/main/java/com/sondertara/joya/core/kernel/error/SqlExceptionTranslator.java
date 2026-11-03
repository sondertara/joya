package com.sondertara.joya.core.kernel.error;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;

/**
 * SQL 异常翻译扩展点：把驱动相关的 {@link SQLException} 转成框架语义异常。
 *
 * <p>默认实现保留原始 SQLException 作为 cause，便于排障；自定义实现可按 SQLState
 * 分类为约束冲突 / 死锁 / 超时等语义异常，供重试策略与上层统一处理。
 */
@FunctionalInterface
public interface SqlExceptionTranslator {

    RuntimeException translate(String task, @Nullable SqlStatement statement, SQLException ex);

    SqlExceptionTranslator DEFAULT = (task, statement, ex) ->
            new DbException("SQL execution failed: " + task + (statement == null ? "" : " | " + statement.sql()), ex);
}
