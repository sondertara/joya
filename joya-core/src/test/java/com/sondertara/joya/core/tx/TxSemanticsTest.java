package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.JoyaClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式事务值语义与连接状态还原不变量测试。
 *
 * <p>针对 {@code com.sondertara.joya.core.tx} 包编写，
 * 用 {@link ConnectionSpy} 观测对 JDBC 连接发出的指令序列。
 */
class TxSemanticsTest {

    private static JoyaClient client(ConnectionSpy spy) {
        return JoyaClient.create(spy.dataSource());
    }

    @Test
    void singleTransaction_commitsOnceAndRestoresConnection() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx(tx -> {
            // 无 SQL，仅验证事务边界
        });

        assertEquals(1, spy.commitCount(), "应恰好 COMMIT 一次: " + spy.opsAsString());
        assertEquals(0, spy.rollbackCount(), "不应 ROLLBACK: " + spy.opsAsString());
        assertEquals(1, spy.borrowCount(), "应只借一次连接: " + spy.opsAsString());
        assertTrue(spy.currentAutoCommit(), "事务结束后应恢复 autoCommit=true: " + spy.opsAsString());
        assertTrue(spy.isClosed(), "连接应已归还: " + spy.opsAsString());
    }

    @Test
    void singleTransaction_rollbackOnException() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        assertThrows(IllegalStateException.class, () ->
                client(spy).tx(tx -> {
                    throw new IllegalStateException("business failure");
                }));

        assertEquals(1, spy.rollbackCount(), "异常应触发 ROLLBACK: " + spy.opsAsString());
        assertEquals(0, spy.commitCount(), "不应 COMMIT: " + spy.opsAsString());
    }

    @Test
    void txCall_returnsValueAndCommitsOnce() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        int answer = client(spy).txCall(tx -> {
            tx.sql("UPDATE t SET a = 1").update();
            return 42;
        });

        assertEquals(42, answer);
        assertEquals(1, spy.commitCount());
        assertEquals(0, spy.rollbackCount());
    }

    @Test
    void savepointCall_returnsValueAndReleasesSavepointWithoutCommittingOuter() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        String res = client(spy).txCall(tx -> {
            String innerVal = tx.savepointCall(sp -> {
                sp.sql("UPDATE t SET a = 1").update();
                return "sp-ok";
            });
            assertEquals(0, spy.commitCount(), "savepoint 结束不得提交外层事务");
            return innerVal;
        });

        assertEquals("sp-ok", res);
        assertEquals(1, spy.commitCount(), "只有外层事务结束才发出一次 COMMIT");
    }

    @Test
    void I7_markedRollbackOnlyMustRefuseCommit() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        assertThrows(UnexpectedRollbackException.class, () ->
                client(spy).tx().run(tx -> {
                    tx.setRollbackOnly();
                }));

        assertEquals(0, spy.commitCount(),
                "标记为仅回滚后绝不能提交: " + spy.opsAsString());
        assertEquals(1, spy.rollbackCount(), "应走向回滚: " + spy.opsAsString());
    }

    @Test
    void I5_noIsValidCallDuringTransaction() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx().run(tx -> {
            for (int i = 0; i < 100; i++) {
                tx.sql("UPDATE t SET a = 1").update();
            }
        });

        assertEquals(0, spy.isValidCount(),
                "事务活跃期间 isValid 调用次数必须为 0（不变量 I5）: " + spy.opsAsString());
        assertEquals(1, spy.borrowCount(),
                "事务期内应复用同一条连接，不得重复借出: " + spy.opsAsString());
    }

    @Test
    void I4_isolationRestoredToBorrowedValue() {
        ConnectionSpy spy = ConnectionSpy.withPool(true, java.sql.Connection.TRANSACTION_READ_COMMITTED);
        int original = spy.currentIsolation();

        client(spy).tx()
                .isolation(Isolation.SERIALIZABLE)
                .run(tx -> tx.savepoint(inner -> {
                    // 保存点不得覆盖还原点
                }));

        assertEquals(original, spy.currentIsolation(),
                "事务结束后隔离级别必须还原到借出前的值: " + spy.opsAsString());
    }

    @Test
    void P0_3_manualCommitPoolBehavesIdentically() {
        ConnectionSpy manualPool = ConnectionSpy.manualCommitPool();

        client(manualPool).tx().run(tx -> {
            // 池默认 autoCommit=false
        });

        assertEquals(1, manualPool.commitCount(),
                "autoCommit=false 的池下事务仍应正常提交一次: " + manualPool.opsAsString());
        assertEquals(0, manualPool.rollbackCount(),
                "不应误判为已在事务中而回滚: " + manualPool.opsAsString());
    }

    @Test
    void I6_closeIsIdempotent() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        Tx tx = client(spy).tx().begin();
        tx.sql("UPDATE t SET a = 1").update();

        tx.close();
        int afterFirst = spy.closeCount();
        tx.close();
        tx.close();

        assertEquals(afterFirst, spy.closeCount(),
                "重复 close 不得重复物理关闭（不变量 I6）: " + spy.opsAsString());
        assertEquals(1, spy.closeCount(), "物理关闭应恰好一次: " + spy.opsAsString());
    }

    @Test
    void I8_businessExceptionIsPrimary() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                client(spy).tx().run(tx -> {
                    throw new IllegalStateException("real business cause");
                }));

        assertEquals("real business cause", thrown.getMessage(),
                "业务异常必须原样传播，不得被清理阶段异常覆盖");
    }
}
