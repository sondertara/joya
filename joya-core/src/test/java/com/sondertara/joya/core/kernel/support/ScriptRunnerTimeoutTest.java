package com.sondertara.joya.core.kernel.support;

import com.sondertara.joya.core.kernel.statement.DbType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E-1 回归：脚本内每条语句执行前都必须重检剩余超时（事务 deadline 是全局预算，不是借连接时的一次性快照）。
 */
class ScriptRunnerTimeoutTest {

    @Test
    void timeoutIsRecheckedBeforeEveryStatement() {
        List<Integer> applied = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        IntSupplier remaining = () -> calls.getAndIncrement() == 0 ? 5 : 3;

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> recordingStatement(applied);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "script-runner-connection";
                    default -> null;
                });

        new ScriptRunner(connection, DbType.h2, remaining)
                .runScript(new StringReader("SELECT 1; SELECT 2;"));

        assertEquals(List.of(5, 3), applied, "两条语句必须分别重检剩余超时");
    }

    private static Statement recordingStatement(List<Integer> applied) {
        return (Statement) Proxy.newProxyInstance(
                ScriptRunnerTimeoutTest.class.getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> {
                        applied.add((Integer) args[0]);
                        yield null;
                    }
                    case "execute" -> false;
                    case "getUpdateCount" -> -1;
                    case "close" -> null;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "recording-statement";
                    default -> null;
                });
    }
}
