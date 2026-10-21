package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.jdbc.callback.ConnectionCallback;
import com.sondertara.joya.core.jdbc.resultset.mapper.ResultSetExtractor;
import com.sondertara.joya.core.jdbc.resultset.mapper.RowMapper;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.io.Reader;
import java.util.List;
import java.util.stream.Stream;

/**
 * SQL 执行端口（端口适配器模式）。
 * <p>
 * 唯一约定：只认 {@link SqlStatement} / {@link BatchSqlStatement}，不感知 Entity / Query / 事务 / 业务语义。
 * 连接来源由实现注入的 {@code ConnectionProvider} 决定。
 */
public interface Executor {

    <T> T query(SqlStatement statement, ResultSetExtractor<T> extractor);

    <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper);

    /**
     * 流式查询。返回的 {@link Stream} 必须在 {@code try-with-resources} 中使用，
     * 关闭时才归还连接，避免连接泄漏。
     */
    <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper);

    int update(SqlStatement statement);

    /** 插入并返回首个生成键；无生成键时返回 {@code null}。 */
    Object insert(SqlStatement statement);

    /** 列级绑定的批量执行。 */
    int[] batchUpdate(BatchSqlStatement batch);

    /** 以 {@code template.sql()} 为模板，对每组参数各执行一次（template 自身参数忽略）。 */
    int[] batchUpdate(SqlStatement template, List<Object[]> rows);

    /** 在一条连接上执行回调（逃生舱）。 */
    <T> T withConnection(ConnectionCallback<T> action);

    /** 执行 SQL 脚本。 */
    void runScript(Reader reader);
}
