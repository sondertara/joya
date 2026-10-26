package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.executor.ResultSetExtractor;
import com.sondertara.joya.core.kernel.executor.RowMapper;
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

    /**
     * 按指定方言游标 {@code fetchSize} 执行流式查询。
     */
    default <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper, int fetchSize) {
        return queryStream(statement, mapper);
    }

    int update(SqlStatement statement);

    /** 插入并返回首个生成键；无生成键时返回 {@code null}。 */
    Object insert(SqlStatement statement);

    /** 单条 INSERT 执行结果切片（受影响行数 + 首个生成键）。 */
    record InsertSlice(int affectedRows, Object generatedKey) {
    }

    /** 执行 INSERT 并返回真实受影响行数与首个生成键。 */
    default InsertSlice insertExecute(SqlStatement statement) {
        return new InsertSlice(1, insert(statement));
    }

    /** 列级绑定的批量执行（返回逐行计数数组）。 */
    int[] batchUpdate(BatchSqlStatement batch);

    /** 列级绑定的批量执行（支持分片 Flush 与生成键收集）。 */
    default BatchSlice batchExecute(BatchSqlStatement batch) {
        return BatchSlice.of(batchUpdate(batch), null);
    }

    /** 以 {@code template.sql()} 为模板，对每组参数各执行一次（template 自身参数忽略）。 */
    int[] batchUpdate(SqlStatement template, List<Object[]> rows);

    /** 在一条连接上执行回调（逃生舱）。 */
    <T> T withConnection(ConnectionCallback<T> action);

    /** 执行 SQL 脚本。 */
    void runScript(Reader reader);
}
