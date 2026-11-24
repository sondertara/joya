package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.dialect.PaginationStyle;
import com.sondertara.joya.core.kernel.observe.ExecutionObserver;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.BatchSlot;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlStatement} record 契约测试：不可变性、防御拷贝、静态工厂语义、参数元数据。
 */
class SqlStatementTest {

    @Test
    void staticFactories_setOperation() {
        assertEquals(Operation.SELECT, SqlStatement.select("SELECT 1").metadata().operation());
        assertEquals(Operation.INSERT, SqlStatement.insert("INSERT INTO t VALUES (?)", 1).metadata().operation());
        assertEquals(Operation.UPDATE, SqlStatement.update("UPDATE t SET a = ?", 1).metadata().operation());
        assertEquals(Operation.DELETE, SqlStatement.delete("DELETE FROM t WHERE id = ?", 1).metadata().operation());
    }

    @Test
    void staticFactories_carryArgs() {
        SqlStatement s = SqlStatement.select("SELECT * FROM t WHERE a = ? AND b = ?", List.of(1, 2));
        assertEquals(List.of(1, 2), s.args());
        assertEquals(2, s.parameters().size());
    }

    @Test
    void of_carriesMetadata() {
        SqlStatement s = SqlStatement.of("SELECT 1", List.of(),
                StatementMetadata.of(Operation.SELECT, DbType.oracle));

        assertEquals("SELECT 1", s.sql());
        assertTrue(s.parameters().isEmpty());
        assertEquals(Operation.SELECT, s.metadata().operation());
        assertEquals(DbType.oracle, s.metadata().dbType());
    }

    @Test
    void withMethods_returnNewInstance_andDoNotMutateOriginal() {
        SqlStatement original = SqlStatement.select("SELECT * FROM t_user", 1);

        SqlStatement rewritten = original
                .withSql("SELECT * FROM t_user_0")
                .withDbType(DbType.mysql);

        assertNotSame(original, rewritten);
        assertEquals("SELECT * FROM t_user", original.sql(), "原实例不应被改写");
        assertEquals("SELECT * FROM t_user_0", rewritten.sql());
        assertEquals(DbType.mysql, rewritten.metadata().dbType());
        assertEquals(Operation.SELECT, rewritten.metadata().operation(), "改写不应丢失操作类型");
        assertEquals(List.of(1), rewritten.args(), "改写不应丢失参数");
    }


    @Test
    void parameters_defensiveCopyAndImmutable() {
        List<SqlParameter> mutable = new ArrayList<>();
        mutable.add(SqlParameter.of(1, "a", 1, ParameterSource.RAW));

        SqlStatement s = SqlStatement.of("SELECT 1", mutable, StatementMetadata.empty());
        mutable.clear();

        assertEquals(1, s.parameters().size(), "record 应防御性拷贝参数列表");
        assertThrows(UnsupportedOperationException.class,
                () -> s.parameters().add(SqlParameter.of(2, "b", 2, ParameterSource.RAW)),
                "语句参数列表应不可变");
    }

    @Test
    void metadata_emptyDefaults() {
        StatementMetadata empty = StatementMetadata.empty();
        assertEquals(null, empty.operation());
        assertEquals(Set.of(), empty.tables());
        assertEquals(null, empty.dbType());
    }

    @Test
    void metadata_builderStyle() {
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .dbType(DbType.postgresql)
                .table(TableRef.of("t_order", "o"))
                .entityType(String.class)
                .build();

        assertEquals(Operation.INSERT, metadata.operation());
        assertEquals(DbType.postgresql, metadata.dbType());
        assertEquals(Set.of(TableRef.of("t_order", "o")), metadata.tables());
        assertEquals(String.class, metadata.entityType());
    }

    @Test
    void batchWithSql_validatesPlaceholderCountAgainstSlots() {
        BatchSqlStatement batch = new BatchSqlStatement(
                "INSERT INTO t_user (id, name) VALUES (?1, ?2)",
                List.of(ColumnBinding.of("id"), ColumnBinding.of("name")),
                List.<Object[]>of(new Object[]{1L, "alice"}),
                StatementMetadata.of(Operation.INSERT, DbType.h2),
                100,
                false,
                null);

        BatchSqlStatement normalized = batch.withSql("INSERT INTO t_user (id, name) VALUES (?, ?)");
        assertEquals("INSERT INTO t_user (id, name) VALUES (?, ?)", normalized.sql());

        assertThrows(IllegalArgumentException.class,
                () -> batch.withSql("INSERT INTO t_user (id) VALUES (?)"),
                "withSql 改变有效占位符数量时必须立即拒绝");
    }

    @Test
    void statementPreparer_rejectsResidualBatchRowRefInSingleRowStatement() {
        StatementPreparer preparer = new StatementPreparer(
                List.of(),
                DialectStrategy.fixed(Dialect.H2),
                new com.sondertara.joya.core.kernel.connection.ConnectionProvider() {
                    @Override
                    public Connection acquire() {
                        throw new AssertionError("should not acquire connection");
                    }

                    @Override
                    public void release(Connection connection) {
                    }
                },
                com.sondertara.joya.core.naming.NamingPolicy.DEFAULT);
        com.sondertara.joya.core.kernel.statement.AstStatement leakedAst =
                new com.sondertara.joya.core.kernel.statement.AstStatement(
                        new com.sondertara.joya.core.kernel.statement.SqlNode.Insert(
                                com.sondertara.joya.core.kernel.statement.SqlNodes.table("t_user", "t_user"),
                                List.of("id"),
                                List.of(List.of(com.sondertara.joya.core.kernel.statement.SqlNodes.rowParam(
                                        "id", 0, ParameterSource.INSERT)))),
                        StatementMetadata.of(Operation.INSERT, DbType.h2));

        assertThrows(IllegalStateException.class, () -> preparer.prepare(leakedAst));

        assertThrows(IllegalArgumentException.class,
                () -> new com.sondertara.joya.core.kernel.statement.SqlNode.Param.RowSlot(-1),
                "RowSlot 下标不得为负数");

        BatchSqlStatement mismatchedBatch = new BatchSqlStatement(
                "INSERT INTO t_user (id, name) VALUES (?, ?)",
                List.of(ColumnBinding.of("id")),
                List.<Object[]>of(new Object[]{1L}),
                StatementMetadata.of(Operation.INSERT, DbType.h2),
                100,
                false,
                null);
        assertThrows(IllegalArgumentException.class, () -> preparer.prepareBatch(mismatchedBatch),
                "prepareBatch 必须统一拦截无改写路径的占位符与槽位数量失配");
    }

    @Test
    void executionObserver_resultMapperFailure_doesNotTriggerOnError() {
        AtomicBoolean onErrorCalled = new AtomicBoolean();
        ExecutionObserver observer = new ExecutionObserver(List.of(new SqlExecutionListener() {
            @Override
            public void onError(SqlExecutionContext ctx, Throwable error, long elapsedNanos) {
                onErrorCalled.set(true);
            }
        }));

        assertThrows(IllegalStateException.class, () -> observer.observe(
                SqlStatement.select("SELECT 1"),
                () -> "ok",
                ignored -> {
                    throw new IllegalStateException("summary mapping boom");
                }));
        org.junit.jupiter.api.Assertions.assertFalse(onErrorCalled.get(),
                "SQL 已执行成功，后置 resultMapper 抛异常不得误报 onError");
    }

    @Test
    void paginationStyle_tagsAppendedParametersWithPaginationSource() {
        SqlStatement base = SqlStatement.select("SELECT id FROM t_user WHERE status = ?", 1);
        SqlStatement paged = PaginationStyle.LIMIT_OFFSET.paginate(base, 20L, 10L);

        assertEquals(3, paged.parameters().size());
        assertEquals(ParameterSource.RAW, paged.parameters().get(0).source());
        assertEquals(ParameterSource.PAGINATION, paged.parameters().get(1).source());
        assertEquals(ParameterSource.PAGINATION, paged.parameters().get(2).source());
    }
}
