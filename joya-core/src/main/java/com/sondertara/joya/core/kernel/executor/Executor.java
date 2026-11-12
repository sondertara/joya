package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.jspecify.annotations.Nullable;

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

    /**
     * 执行器工厂：{@code ExecutionKernel} 在构造与 {@code bind(...)} 派生事务视图时，
     * 用对应的连接来源与异常翻译器创建执行器，使执行器成为真正可替换的接缝。
     */
    @FunctionalInterface
    interface Factory {
        Executor create(ConnectionProvider connections, SqlExceptionTranslator exceptionTranslator);
    }

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

    /** 单条 INSERT 执行结果切片（受影响行数 + 首个生成键）。 */
    record InsertSlice(int affectedRows, Object generatedKey) {
    }

    /** 执行 INSERT 并返回真实受影响行数与首个生成键。 */
    InsertSlice insertExecute(SqlStatement statement);

    /** 列级绑定的批量执行（支持分片 Flush 与生成键收集）。 */
    BatchSlice batchExecute(BatchSqlStatement batch);

    /** 在一条连接上执行回调（逃生舱）。 */
    <T> T withConnection(ConnectionCallback<T> action);

    /** 执行 SQL 脚本。 */
    void runScript(Reader reader);

    /** 按可选已知方言词法规则执行 SQL 脚本（默认回退 {@link #runScript(Reader)}）。 */
    default void runScript(Reader reader, @Nullable DbType dbType) {
        runScript(reader);
    }
}
