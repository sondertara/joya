package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.JoyaClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void I8_businessExceptionIsPrimaryAndRollbackFailureIsSuppressed() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnRollback();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                client(spy).tx().run(tx -> {
                    throw new IllegalStateException("real business cause");
                }));

        assertEquals("real business cause", thrown.getMessage(),
                "业务异常必须原样传播，不得被清理阶段异常覆盖");
        assertTrue(thrown.getSuppressed().length >= 1,
                "回滚阶段抛出的异常应通过 addSuppressed 挂载在主业务异常上（不变量 I8）");
    }

    @Test
    void I4_readOnlyRestoredToBorrowedValue() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx()
                .readOnly()
                .run(tx -> {
                    assertTrue(spy.currentReadOnly(), "事务期间应设置为 readOnly=true");
                });

        assertEquals(false, spy.currentReadOnly(),
                "事务结束后 readOnly 状态必须还原为借出前的 false: " + spy.opsAsString());
        assertTrue(spy.ops().contains("setReadOnly(true)"));
        assertTrue(spy.ops().contains("setReadOnly(false)"));
    }

    @Test
    void I9_crossThreadAccessToTxIsRejected() throws Exception {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx().run(tx -> {
            java.util.concurrent.atomic.AtomicReference<Throwable> threadError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    tx.sql("UPDATE t SET a = 1").update();
                } catch (Throwable t) {
                    threadError.set(t);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(threadError.get() instanceof TxException
                            && threadError.get().getMessage().contains("invariant I9"),
                    "跨线程使用 Tx 必须抛出 TxException（不变量 I9），实际: " + threadError.get());
        });
    }

    @Test
    void retryOnDeadlock_retriesOnSqlState40xxxAndStopsAtMaxAttempts() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger(0);

        String result = client(spy).tx()
                .retryOnDeadlock(3)
                .call(tx -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new com.sondertara.joya.exceptions.DbException(
                                new java.sql.SQLException("deadlock detected", "40001"));
                    }
                    return "recovered";
                });

        assertEquals("recovered", result);
        assertEquals(3, attempts.get(), "前两次死锁应触发重试并在第三次成功");
        assertEquals(2, spy.rollbackCount(), "前两次失败应各回滚一次");
        assertEquals(1, spy.commitCount(), "第三次成功应提交一次");

        // 超出最大重试次数时应原样抛出最后一次死锁异常
        java.util.concurrent.atomic.AtomicInteger exhaustedAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
        assertThrows(com.sondertara.joya.exceptions.DbException.class, () ->
                client(spy).tx()
                        .retryOnDeadlock(2)
                        .run(tx -> {
                            exhaustedAttempts.incrementAndGet();
                            throw new com.sondertara.joya.exceptions.DbException(
                                    new java.sql.SQLException("deadlock detected", "40P01"));
                        }));
        assertEquals(2, exhaustedAttempts.get(), "最多尝试 maxAttempts 次");

        // SQLState 40003 (statement_completion_unknown) 不得自动重试，防止非幂等重复执行
        java.util.concurrent.atomic.AtomicInteger unknownCompletionAttempts = new java.util.concurrent.atomic.AtomicInteger(0);
        assertThrows(com.sondertara.joya.exceptions.DbException.class, () ->
                client(spy).tx()
                        .retryOnDeadlock(3)
                        .run(tx -> {
                            unknownCompletionAttempts.incrementAndGet();
                            throw new com.sondertara.joya.exceptions.DbException(
                                    new java.sql.SQLException("statement completion unknown", "40003"));
                        }));
        assertEquals(1, unknownCompletionAttempts.get(), "40003 严禁重试");
    }

    @Test
    void rollbackFailure_mustNotRestoreAutoCommitTrue_andReportsUnknownOutcome() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnRollback();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> observedOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThrows(IllegalStateException.class, () ->
                client(spy).tx().run(tx -> {
                    tx.afterCompletion(observedOutcome::set);
                    tx.sql("UPDATE t SET a = 1").update();
                    throw new IllegalStateException("trigger rollback");
                }));

        // 严禁在 rollback 失败后执行 setAutoCommit(true)，否则 JDBC 会隐式提交未回滚的脏写
        assertEquals(List.of("setAutoCommit(false)", "EXEC", "ROLLBACK", "CLOSE"), spy.ops(),
                "rollback 失败后严禁执行 setAutoCommit(true): " + spy.opsAsString());
        assertEquals(TxOutcome.UNKNOWN, observedOutcome.get(),
                "rollback 抛异常时事务终态应为 UNKNOWN");
    }

    @Test
    void commitFailure_reportsUnknownOutcomeToAfterCompletion() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnCommit();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> observedOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThrows(TxException.class, () ->
                client(spy).tx().run(tx -> {
                    tx.afterCompletion(observedOutcome::set);
                    tx.sql("UPDATE t SET a = 1").update();
                }));

        assertEquals(TxOutcome.UNKNOWN, observedOutcome.get(),
                "commit 抛异常时事务终态应为 UNKNOWN 而非 ROLLED_BACK");
    }

    @Test
    void savepointRollbackFailure_marksOuterTransactionRollbackOnly() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnSavepointRollback();

        assertThrows(UnexpectedRollbackException.class, () ->
                client(spy).tx().run(tx -> {
                    RuntimeException innerEx = assertThrows(RuntimeException.class, () ->
                            tx.savepoint(sp -> {
                                sp.sql("UPDATE t SET a = 1").update();
                                throw new RuntimeException("inner failure");
                            }));
                    assertTrue(innerEx.getSuppressed().length >= 1, "savepoint 回滚异常应作为 suppressed 挂载");
                    assertTrue(tx.isRollbackOnly(), "savepoint 回滚失败后外层事务必须标记为 rollbackOnly");
                }));
    }

    @Test
    void savepoint_scopesAfterCommitCallbacks_andForbidsDirectCommitOrRollback() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        List<String> committedHooks = new java.util.ArrayList<>();

        client(spy).tx().run(tx -> {
            tx.afterCommit(() -> committedHooks.add("outer"));

            // 1. 回滚的 savepoint 内注册的 afterCommit 必须被丢弃
            assertThrows(RuntimeException.class, () ->
                    tx.savepoint(sp -> {
                        sp.afterCommit(() -> committedHooks.add("rolled_back_sp"));
                        throw new RuntimeException("abort sp");
                    }));

            // 2. 提交的 savepoint 内注册的 afterCommit 在外层提交后正常触发
            tx.savepoint(sp -> {
                sp.afterCommit(() -> committedHooks.add("committed_sp"));
            });

            // 3. savepoint 作用域内直接调用 commit() / rollback() 必须抛 IllegalTxStateException
            tx.savepoint(sp -> {
                assertThrows(IllegalTxStateException.class, sp::commit);
                assertThrows(IllegalTxStateException.class, sp::rollback);
            });
        });

        assertEquals(List.of("outer", "committed_sp"), committedHooks);
    }

    @Test
    void explicitCommitOrRollbackInsideTxCallback_isAllowedWithoutDoubleCommitFailure() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx().run(tx -> {
            tx.sql("UPDATE t SET a = 1").update();
            tx.commit();
        });
        assertEquals(1, spy.commitCount());

        ConnectionSpy spyRollback = ConnectionSpy.autoCommitPool();
        client(spyRollback).tx().run(tx -> {
            tx.sql("UPDATE t SET a = 1").update();
            tx.rollback();
        });
        assertEquals(1, spyRollback.rollbackCount());
        assertEquals(0, spyRollback.commitCount());
    }

    @Test
    void manualCommitPool_nonTransactionalExecutionEnforcesAutoCommitTrueAndRestoresFalse() {
        ConnectionSpy spy = ConnectionSpy.manualCommitPool();
        JoyaClient joya = client(spy);

        joya.sql("UPDATE t SET a = 1").update();

        assertEquals(List.of("setAutoCommit(true)", "EXEC", "setAutoCommit(false)", "CLOSE"), spy.ops(),
                "manualCommitPool 非事务执行必须开启 autoCommit=true 并在归还前恢复 false: " + spy.opsAsString());
    }

    @Test
    void customConnectionProviderAndClientQueryTimeout_areHonoredInTx() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        java.util.concurrent.atomic.AtomicInteger customAcquireCount = new java.util.concurrent.atomic.AtomicInteger(0);
        com.sondertara.joya.core.kernel.connection.ConnectionProvider customProvider =
                new com.sondertara.joya.core.kernel.connection.ConnectionProvider() {
                    @Override
                    public java.sql.Connection acquire() throws java.sql.SQLException {
                        customAcquireCount.incrementAndGet();
                        return spy.dataSource().getConnection();
                    }

                    @Override
                    public void release(java.sql.Connection connection) throws java.sql.SQLException {
                        connection.close();
                    }

                    @Override
                    public java.util.OptionalInt queryTimeoutSeconds() {
                        return java.util.OptionalInt.empty();
                    }
                };

        JoyaClient joya = JoyaClient.builder(spy.dataSource())
                .settings(com.sondertara.joya.core.JoyaSettings.builder().queryTimeoutSeconds(12).build())
                .connectionProvider(customProvider)
                .build();

        joya.tx().run(tx -> {
            tx.sql("UPDATE t SET a = 1").update();
        });

        assertEquals(1, customAcquireCount.get(), "JdbcTxEngine 应走自定义 ConnectionProvider");
        assertEquals(12, spy.queryTimeoutSeconds(), "自定义 provider 空超时时必须回退 ExecutionOptions");
    }

    @Test
    void retryAfterCommittedRetryableFailure_isForbidden() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();

        assertThrows(com.sondertara.joya.exceptions.DbException.class, () ->
                client(spy).tx().retryOnDeadlock(3).run(tx -> {
                    tx.afterCompletion(outcome::set);
                    attempts.incrementAndGet();
                    tx.commit();
                    throw new com.sondertara.joya.exceptions.DbException(
                            new java.sql.SQLException("retryable after commit", "40001"));
                }));

        assertEquals(1, attempts.get(), "已 COMMITTED 后即使异常 retryable 也绝不能重放事务体");
        assertEquals(1, spy.commitCount());
        assertEquals(0, spy.rollbackCount());
        assertEquals(TxOutcome.COMMITTED, outcome.get());
    }

    @Test
    void retryAfterUnknownCommitFailure_isForbidden() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnCommit("40001");
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();

        assertThrows(TxException.class, () ->
                client(spy).tx().retryOnDeadlock(3).run(tx -> {
                    tx.afterCompletion(outcome::set);
                    attempts.incrementAndGet();
                }));

        assertEquals(1, attempts.get(), "提交结果 UNKNOWN 时禁止根据 retryable cause 重试");
        assertEquals(1, spy.commitCount());
        assertEquals(TxOutcome.UNKNOWN, outcome.get());
    }

    @Test
    void retryAfterRollbackFailure_isForbidden() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnRollback();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();

        com.sondertara.joya.exceptions.DbException thrown = assertThrows(
                com.sondertara.joya.exceptions.DbException.class, () ->
                        client(spy).tx().retryOnDeadlock(3).run(tx -> {
                            tx.afterCompletion(outcome::set);
                            attempts.incrementAndGet();
                            throw new com.sondertara.joya.exceptions.DbException(
                                    new java.sql.SQLException("deadlock", "40P01"));
                        }));

        assertEquals(1, attempts.get(), "回滚失败时结局 UNKNOWN，禁止重试");
        assertEquals(TxOutcome.UNKNOWN, outcome.get());
        assertTrue(thrown.getSuppressed().length > 0, "回滚故障必须保留为 suppressed");
    }

    @Test
    void retryAfterRollbackSucceededButCloseCleanupFailed_isForbidden() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnRestoreAutoCommit();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();

        com.sondertara.joya.exceptions.DbException thrown = assertThrows(
                com.sondertara.joya.exceptions.DbException.class, () ->
                        client(spy).tx().retryOnDeadlock(3).run(tx -> {
                            tx.afterCompletion(outcome::set);
                            attempts.incrementAndGet();
                            throw new com.sondertara.joya.exceptions.DbException(
                                    new java.sql.SQLException("deadlock", "40001"));
                        }));

        assertEquals(1, attempts.get(), "回滚虽成功但连接清理失败时仍禁止重试");
        assertEquals(1, spy.rollbackCount());
        assertEquals(TxOutcome.ROLLED_BACK, outcome.get(), "后续清理失败不得改写已知回滚结局");
        assertTrue(thrown.getSuppressed().length > 0, "关闭清理故障必须保留为 suppressed");
    }

    @Test
    void crossThreadCommit_isRejectedWithoutChangingOutcome() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        Tx tx = client(spy).tx().begin();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        tx.afterCompletion(outcome::set);

        Throwable error = captureOnOtherThread(tx::commit);

        assertTrue(error instanceof TxException && error.getMessage().contains("invariant I9"),
                "跨线程 commit 必须被所有者校验拒绝: " + error);
        assertNull(outcome.get(), "owner 异常不得触发 complete(UNKNOWN)");
        assertEquals(0, spy.commitCount());
        tx.close();
        assertEquals(TxOutcome.ROLLED_BACK, outcome.get());
    }

    @Test
    void crossThreadClose_isRejectedWithoutClosingConnection() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        Tx tx = client(spy).tx().begin();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        tx.afterCompletion(outcome::set);

        Throwable error = captureOnOtherThread(tx::close);

        assertTrue(error instanceof TxException && error.getMessage().contains("invariant I9"),
                "跨线程 close 必须被所有者校验拒绝: " + error);
        assertNull(outcome.get());
        assertFalse(spy.isClosed(), "跨线程 close 不得归还或关闭物理连接");
        tx.close();
        assertEquals(TxOutcome.ROLLED_BACK, outcome.get());
    }

    @Test
    void illegalSecondCommit_doesNotOverwriteCommittedOutcome() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        Tx tx = client(spy).tx().begin();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        tx.afterCompletion(outcome::set);
        tx.commit();

        assertThrows(IllegalTxStateException.class, tx::commit);

        assertEquals(TxOutcome.COMMITTED, outcome.get(), "非法状态异常不得污染已知 COMMITTED 结局");
        tx.close();
    }

    @Test
    void closeAfterSuccessfulRollbackAndFailedRestore_preservesRolledBackOutcome() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnRestoreAutoCommit();
        Tx tx = client(spy).tx().begin();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        tx.afterCompletion(outcome::set);

        assertThrows(TxException.class, tx::close);

        assertEquals(1, spy.rollbackCount());
        assertEquals(1, spy.closeCount());
        assertEquals(TxOutcome.ROLLED_BACK, outcome.get(),
                "物理回滚已确认后，还原或关闭失败不得把结局改成 UNKNOWN");
    }

    @Test
    void beginOptionError_isRethrownAfterSymmetricCleanup() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failWithErrorOnReadOnly();
        java.util.concurrent.atomic.AtomicBoolean bodyCalled = new java.util.concurrent.atomic.AtomicBoolean();

        assertThrows(AssertionError.class, () -> client(spy).tx().readOnly().run(tx -> bodyCalled.set(true)));

        assertFalse(bodyCalled.get());
        assertEquals(1, spy.rollbackCount(), "begin 半开启后即使抛 Error 也必须回滚");
        assertEquals(1, spy.closeCount(), "begin 失败后必须归还物理连接");
        assertTrue(spy.currentAutoCommit(), "清理后必须还原借出时的 autoCommit");
    }

    @Test
    void savepointAfterCommitRegistration_failsBeforeSavepointRelease() throws Exception {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        TxConnection delegate = new TxConnection(spy.connection());
        delegate.begin(TxOptions.DEFAULT);
        TxImpl tx = new TxImpl(client(spy).template(), rejectAfterCommitRegistration(delegate));
        java.util.concurrent.atomic.AtomicBoolean callbackRan = new java.util.concurrent.atomic.AtomicBoolean();

        assertThrows(TxException.class, () -> tx.savepoint(sp ->
                sp.afterCommit(() -> callbackRan.set(true))));

        assertTrue(spy.ops().contains("ROLLBACK_TO_SP"), "入队预检失败后应回滚保存点");
        assertFalse(spy.ops().contains("RELEASE_SP"), "不得在释放保存点后才暴露同步器不可用");
        assertFalse(callbackRan.get());
        tx.close();
    }

    @Test
    void txCallReturningDerivedTransactionalStream_rollsBackAndCleansResources() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        assertThrows(TxException.class, () -> client(spy).txCall(tx ->
                tx.sql("SELECT 1").stream(rs -> new Object()).map(value -> value)));

        assertEquals(0, spy.commitCount(), "逃逸 Stream 不得在提交后才失败");
        assertEquals(1, spy.rollbackCount());
        assertEquals(1, spy.resultSetCloseCount());
        assertEquals(1, spy.preparedStatementCloseCount());
        assertTrue(spy.isClosed());
    }

    @Test
    void escapedTransactionalStream_isClosedAtCommitAndRejectsTerminalRead() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        java.util.concurrent.atomic.AtomicReference<Stream<Object>> escaped =
                new java.util.concurrent.atomic.AtomicReference<>();

        client(spy).tx(tx -> escaped.set(tx.sql("SELECT 1").stream(rs -> new Object())));

        assertEquals(1, spy.commitCount());
        assertEquals(1, spy.resultSetCloseCount(), "事务边界必须关闭逃逸流的结果集");
        assertEquals(1, spy.preparedStatementCloseCount());
        TxException error = assertThrows(TxException.class, () -> escaped.get().count());
        assertTrue(error.getMessage().contains("COMMITTED"), "终态读取失败应可诊断: " + error.getMessage());
        escaped.get().close();
    }

    @Test
    void transactionalStream_rejectsCrossThreadReadWithoutAbortingOwnerThread() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        client(spy).tx(tx -> {
            Stream<Object> stream = tx.sql("SELECT 1").stream(rs -> new Object());
            Throwable error = captureOnOtherThread(stream::count);
            assertTrue(error instanceof TxException && error.getMessage().contains("invariant I9"),
                    "跨线程读取事务 Stream 必须失败: " + error);
            stream.close();
        });

        assertEquals(1, spy.commitCount(), "其他线程的非法读取不得污染 owner 线程事务终态");
        assertEquals(0, spy.rollbackCount());
    }

    @Test
    void transactionalStream_canBeConsumedAndClosedInsideBoundary() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();

        long count = client(spy).txCall(tx -> {
            try (Stream<Object> stream = tx.sql("SELECT 1").stream(rs -> new Object())) {
                return stream.count();
            }
        });

        assertEquals(0L, count);
        assertEquals(1, spy.commitCount());
        assertEquals(1, spy.resultSetCloseCount());
        assertEquals(1, spy.preparedStatementCloseCount());
    }

    @Test
    void ordinaryNonTransactionalStreams_keepTheirExistingBehavior() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        try (Stream<Object> stream = client(spy).sql("SELECT 1").stream(rs -> new Object())) {
            assertEquals(0L, stream.count());
        }
        assertTrue(spy.isClosed());
        assertEquals(1, spy.resultSetCloseCount());

        ConnectionSpy txSpy = ConnectionSpy.autoCommitPool();
        try (Stream<Integer> plain = client(txSpy).txCall(tx -> {
            try (Stream<Object> consumed = tx.sql("SELECT 1").stream(rs -> new Object())) {
                assertEquals(0L, consumed.count());
            }
            return Stream.of(1, 2);
        })) {
            assertEquals(2L, plain.count(), "已正常消费并关闭事务 Stream 后返回无关 Stream.of 不应被误判逃逸");
        }
        assertEquals(1, txSpy.commitCount());
    }

    @Test
    void commitError_marksUnknownOutcomeAndDoesNotBecomeRolledBack() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failWithErrorOnCommit();
        java.util.concurrent.atomic.AtomicReference<TxOutcome> observedOutcome =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThrows(AssertionError.class, () ->
                client(spy).tx().run(tx -> {
                    tx.afterCompletion(observedOutcome::set);
                    tx.sql("UPDATE t SET a = 1").update();
                }));

        assertEquals(TxOutcome.UNKNOWN, observedOutcome.get(),
                "commit 抛出 Error 时终态必须记录为 UNKNOWN 而非被清理改写为 ROLLED_BACK");
        assertFalse(spy.ops().contains("setAutoCommit(true)"),
                "commit 抛 Error 后不得恢复 setAutoCommit(true)");
    }

    @Test
    void savepointReleaseFailure_marksOuterTransactionRollbackOnlyAndDiscardsCallbacks() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failOnSavepointRelease();
        java.util.concurrent.atomic.AtomicBoolean spCallbackRan = new java.util.concurrent.atomic.AtomicBoolean();

        assertThrows(UnexpectedRollbackException.class, () ->
                client(spy).tx().run(tx -> {
                    assertThrows(TxException.class, () ->
                            tx.savepoint(sp -> {
                                sp.sql("UPDATE t SET a = 1").update();
                                sp.afterCommit(() -> spCallbackRan.set(true));
                            }));
                    assertTrue(tx.isRollbackOnly(), "savepoint release 失败后外层事务必须标记为 rollbackOnly");
                }));

        assertFalse(spCallbackRan.get(), "releaseSavepoint 失败时不得触发保存点内 afterCommit");
        assertEquals(0, spy.commitCount());
        assertEquals(1, spy.rollbackCount());
    }

    @Test
    void streamCloseEncounteringErrorOnResultSet_stillClosesStatementAndReleasesConnection() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool().failWithErrorOnResultSetClose();
        Stream<Object> stream = client(spy).sql("SELECT 1").stream(rs -> new Object());

        assertThrows(AssertionError.class, stream::close);

        assertEquals(1, spy.resultSetCloseCount());
        assertEquals(1, spy.preparedStatementCloseCount(), "ResultSet.close 抛 Error 时仍必须关闭 PreparedStatement");
        assertTrue(spy.isClosed(), "ResultSet.close 抛 Error 时仍必须归还/关闭连接");
    }

    @Test
    void crossThreadAfterCommitAndAfterCompletion_areRejectedByOwnerCheck() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        Tx tx = client(spy).tx().begin();

        Throwable afterCommitErr = captureOnOtherThread(() -> tx.afterCommit(() -> {}));
        Throwable afterCompletionErr = captureOnOtherThread(() -> tx.afterCompletion(o -> {}));
        Throwable savepointErr = captureOnOtherThread(() -> tx.savepoint(sp -> {}));

        assertTrue(afterCommitErr instanceof TxException && afterCommitErr.getMessage().contains("invariant I9"));
        assertTrue(afterCompletionErr instanceof TxException && afterCompletionErr.getMessage().contains("invariant I9"));
        assertTrue(savepointErr instanceof TxException && savepointErr.getMessage().contains("invariant I9"));
        tx.close();
    }

    @Test
    void scriptRunner_redactsSqlInLogsAndErrorsByDefault() {
        ConnectionSpy spy = ConnectionSpy.autoCommitPool();
        java.io.StringWriter log = new java.io.StringWriter();
        java.io.StringWriter errorLog = new java.io.StringWriter();
        com.sondertara.joya.core.kernel.support.ScriptRunner runner =
                new com.sondertara.joya.core.kernel.support.ScriptRunner(spy.connection());
        runner.setStopOnError(true);
        runner.setLogWriter(new java.io.PrintWriter(log));
        runner.setErrorLogWriter(new java.io.PrintWriter(errorLog));
        String secret = "top-secret-literal";

        com.sondertara.joya.exceptions.DbException thrown = assertThrows(
                com.sondertara.joya.exceptions.DbException.class,
                () -> runner.runScript(new java.io.StringReader(
                        "INSERT INTO audit_log(message) VALUES ('" + secret + "');")));

        assertFalse(log.toString().contains(secret), "正常日志不得包含脚本字面量");
        assertFalse(errorLog.toString().contains(secret), "错误日志不得包含脚本字面量");
        assertFalse(thrown.getMessage().contains(secret), "异常消息同样不得默认泄露脚本");
        assertTrue(log.toString().contains("fingerprint="), "脱敏后仍应保留可关联的诊断指纹");
    }

    private static Throwable captureOnOtherThread(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining test thread", e);
        }
        return error.get();
    }

    private static TxSession rejectAfterCommitRegistration(TxConnection delegate) {
        return (TxSession) java.lang.reflect.Proxy.newProxyInstance(
                TxSession.class.getClassLoader(),
                new Class<?>[]{TxSession.class},
                (proxy, method, args) -> {
                    if ("validateAfterCommitRegistration".equals(method.getName())) {
                        throw new TxException("simulated inactive transaction synchronization");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
