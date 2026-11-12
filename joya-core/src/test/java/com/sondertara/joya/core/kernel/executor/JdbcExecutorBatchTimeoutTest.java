package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.core.kernel.connection.ConnectionProvider;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E-1 回归：批量分片在每次 flush 前都必须重检剩余超时，而不是只在借出连接/prepare 时取一次。
 */
class JdbcExecutorBatchTimeoutTest {

    @Test
    void everyChunkFlushRefreshesTheRemainingTimeout() {
        List<Integer> applied = new ArrayList<>();
        int[] sequence = {4, 3, 2, 1};
        AtomicInteger index = new AtomicInteger();

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> recordingStatement(applied);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "batch-timeout-connection";
                    default -> null;
                });
        ConnectionProvider provider = new ConnectionProvider() {
            @Override
            public Connection acquire() {
                return connection;
            }

            @Override
            public void release(Connection ignored) {
            }

            @Override
            public OptionalInt queryTimeoutSeconds() {
                return OptionalInt.of(sequence[Math.min(index.getAndIncrement(), sequence.length - 1)]);
            }
        };

        SqlStatement template = SqlStatement.of(
                "INSERT INTO t_batch_timeout (id) VALUES (?)",
                List.of(),
                StatementMetadata.of(Operation.INSERT, DbType.h2));
        List<Object[]> rows = List.of(
                new Object[]{1}, new Object[]{2}, new Object[]{3}, new Object[]{4}, new Object[]{5});
        BatchSqlStatement batch = BatchSqlStatement.fromTemplate(template, rows, 2);

        new JdbcExecutor(provider, SqlExceptionTranslator.DEFAULT).batchExecute(batch);

        assertEquals(List.of(4, 3, 2, 1), applied,
                "prepare 一次 + 三个分片 flush 各一次，每次都必须重检剩余超时");
    }

    private static PreparedStatement recordingStatement(List<Integer> applied) {
        return (PreparedStatement) Proxy.newProxyInstance(
                JdbcExecutorBatchTimeoutTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> {
                        applied.add((Integer) args[0]);
                        yield null;
                    }
                    case "executeBatch" -> new int[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "recording-prepared-statement";
                    default -> null;
                });
    }
}
