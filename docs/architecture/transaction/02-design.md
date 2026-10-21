# 02 目标架构

## 设计哲学

> **事务是你持有的值，不是你祈祷已绑定的环境状态。**

推论：**让 bug 结构上不可能，而不是靠纪律避免。** 每条不变量都对应一个已复现的缺陷，且必须由类型或断言强制，不能只写在注释里（现状 `JoyaTransactionScope.java:16` 的注释就是反面教材）。

---

## 九条不变量

| # | 不变量 | 消灭的缺陷 | 强制手段 |
|---|---|---|---|
| I1 | **事务是值**：只有 `Tx` 句柄能执行事务内 SQL | 环境态语义模糊 | 类型系统：`Tx` 上才有 `insertInto` 等方法 |
| I2 | **状态显式**：`TxState` 状态机，绝不从 `autoCommit` 反推 | P0-3 | `Session` 持有 `TxState` 字段 |
| I3 | **只有最外层能物理提交**：`commit`/`rollback`/`setAutoCommit`/隔离级别仅由 `isNew==true` 层执行 | P0-1 | `Session.depth == 0` 断言 |
| I4 | **恢复点是记录**：最外层 begin 捕获一次 `RestorePoint`，结束恢复一次 | P1-1 | `record` 不可变，仅最外层写 |
| I5 | **事务活跃期间连接永不更换**；校验只在借出时且委托池 | P1-3 | `ACTIVE` 态下 `getConnection()` 不校验 |
| I6 | **无引用计数**：`Session` 独占连接所有权，恰好关闭一次 | P1-2 | 删除 `retain`/`release` API |
| I7 | **参与失败 → 全局 rollback-only**，外层 commit 抛 `UnexpectedRollbackException` | 静默数据丢失 | `Session.globalRollbackOnly` 标志 |
| I8 | **异常合成**：业务异常胜出，清理失败一律 `addSuppressed` | P1-4 | 统一 `TxRunner` 收敛，禁止裸 `finally{commit}` |
| I9 | **所有者线程断言**：`Session` 记录 owner，跨线程使用直接抛错 | P0-2 + Loom | 每次 `Session` 访问校验 `Thread.currentThread()` |

### I3 的具体含义（最重要）

```
外层 begin(REQUIRED)   → isNew=true,  depth=0  → 捕获 RestorePoint、setAutoCommit(false)、设隔离级别
内层 begin(REQUIRED)   → isNew=false, depth=1  → 什么都不碰，只登记参与者
内层 commit            → 只 depth--，不碰连接
内层 rollback          → 只 depth-- + markGlobalRollbackOnly()
外层 commit            → depth==0 → 检查 globalRollbackOnly → 物理 COMMIT → 恢复 RestorePoint → 归还连接
```

对比现状：内层 cleanup 无条件 `endTransaction()`（`LocalTransactionManager.java:339`）→ P0-1。

---

## 分层

```
┌──────────────────────────────────────────────────────────────────┐
│  用户代码                                                          │
│    joya.tx(tx -> {...})          ← 函数式，首选                    │
│    try (Tx tx = joya.begin())    ← RAII，跨方法/流式                │
│    joya.query(...)               ← 自动提交路径（不经 Tx）          │
│    @Transactional                ← 兼容层，传递环境句柄              │
└───────────────┬──────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────┐
│  Tx（事务句柄）                                                    │
│    · 唯一的事务内 SQL 执行入口                                      │
│    · setRollbackOnly / savepoint / mapper(Class) / on(dsId)       │
│    · 不可跨线程（I9）、不可逃逸出 tx(...)（函数式形态）              │
└───────────────┬──────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────┐
│  Session（连接所有者）                                             │
│    · 独占一个 Connection（I6）                                     │
│    · TxState 状态机 + depth + globalRollbackOnly（I2/I3/I7）       │
│    · RestorePoint（I4） · ownerThread（I9）                        │
└───────────────┬──────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────┐
│  TransactionPlatform（SPI）                                       │
│  ┌────────────┬──────────────────┬──────────────────────────┐    │
│  │ JoyaLocal  │ SpringTx          │ JtaXa                    │    │
│  │ 自管本地事务 │ 经 DataSourceUtils │ 挂 Agroal                │    │
│  │            │ 借连接，绝不 commit │ TransactionIntegration   │    │
│  └────────────┴──────────────────┴──────────────────────────┘    │
└───────────────┬──────────────────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────────────────┐
│  DataSourceRegistry                                              │
│    DataSourceId → Handle{DataSource, Dialect, TxCapabilities}    │
└──────────────────────────────────────────────────────────────────┘
```

### 与现有代码的关系

| 现有 | 去向 |
|---|---|
| `TransactionSynchronization` | **删除**。`IdentityHashMap<DataSource, …>` 的 per-DS 思路被 `DataSourceRegistry` 继承 |
| `ConnectionContext` | **重写为 `Session`**。去引用计数、去 `autoCommit` 推断、去连接热替换 |
| `LocalTransactionManager` | **重写为 `JoyaLocalPlatform`**。实例 Deque → `Session` 内 `depth` 计数 |
| `AbstractTransactionStatus` | 合并入 `Session`（状态机）+ `Tx`（对外句柄） |
| `TransactionTemplate` / `Impl` | **合并入 `TxRunner`**，负责重试 + 异常合成 |
| `TransactionalInterceptor` | 重写。当前是死代码（零引用），新版走 `TxContext` 解析环境句柄 |
| `TransactionScope`（kernel/tx） | 演进为 `Tx`。RAII 直觉保留，补齐执行能力 |
| `JoyaTransactionScope` | 删除，由 `TxImpl` 取代 |
| `Propagation`（7 值） | 拆为 `Propagation`（3 值）+ `TxAssertion`（4 值） |
| `Isolation` | 保留枚举，修正 `DEFAULT.getValue()` 与 `fromJdbcValue(0)` |
| `SavepointManager` | 保留接口，由 `Session` 实现 |

---

## 状态机（I2）

```
                    begin(depth 0→1)
        ┌─────┐ ──────────────────────► ┌────────┐
        │ NONE │                         │ ACTIVE │
        └─────┘ ◄────────────────────── └────┬───┘
             ▲     完成并释放连接              │
             │                     setRollbackOnly / 参与者失败
             │                                │
             │                                ▼
             │                       ┌──────────────────┐
             │                       │ MARKED_ROLLBACK  │
             │                       └────────┬─────────┘
             │                                │
        ┌────┴─────────┐              ┌───────▼──────────┐
        │  COMMITTED   │◄─ commit ─── │  ROLLED_BACK     │
        │  （终态）      │  (depth 0)   │  （终态）          │
        └──────────────┘              └──────────────────┘

合法迁移（其余一律抛 IllegalTxStateException）：
  NONE            → ACTIVE                    begin，depth 0→1
  ACTIVE          → ACTIVE                    参与者 begin/commit，depth ±1
  ACTIVE          → MARKED_ROLLBACK           setRollbackOnly / 参与者失败
  ACTIVE          → COMMITTED                 commit，depth 1→0
  ACTIVE          → ROLLED_BACK               rollback，depth 1→0
  MARKED_ROLLBACK → ROLLED_BACK               commit 或 rollback，depth 1→0
  MARKED_ROLLBACK → COMMITTED                 ✗ 禁止 → UnexpectedRollbackException
  COMMITTED       → *                         ✗ 终态
  ROLLED_BACK     → *                         ✗ 终态
```

关键点：`MARKED_ROLLBACK` 状态下调 `commit()` **不是静默回滚**，而是回滚后抛 `UnexpectedRollbackException`（I7）。现状是静默走回滚路径（`LocalTransactionManager.java:122-128`），调用方以为提交成功了。

---

## 传播语义收敛

### 运行时 3 值

| 值 | 语义 | 实现 |
|---|---|---|
| `REQUIRED` | 有则加入（仅登记 `depth++`），无则新建 | 加入时**不碰连接**（I3） |
| `REQUIRES_NEW` | 总是独立事务 | 独立 `Session` + 独立连接，外层 `Session` 原样挂起（不改状态） |
| `NESTED` | savepoint 子事务 | 要求 `depth > 0`；`depth == 0` 时**报错**而非退化为新事务 |

`NESTED` 在无外层时报错，是刻意收紧：现状 `depth == 0` 时会在无事务的连接上直接建 savepoint（P0-3 实测），静默产生错误语义。宁可报错。

### 断言 4 值（不是传播行为）

```java
public enum TxAssertion {
    MANDATORY,      // 必须已在事务中，否则抛错
    NEVER,          // 必须不在事务中，否则抛错
    SUPPORTS,       // 无约束（等价不加断言，保留供文档表达意图）
    NOT_SUPPORTED   // 必须不在事务中；若在，挂起外层并以独立连接非事务执行
}
```

理由：这四个**从来不是"如何传播"，而是"前置条件是否满足"**。放在运行时传播枚举里，导致 `LocalTransactionManager.begin` 的 switch（`:73-106`）把断言失败和事务开启混在一个流程里——`NEVER` 失败路径还得先 `cleanupAfterCompletion` 再抛异常（`:85-87`），这是设计错位的直接症状。

新设计中断言在方法入口独立校验，且**可在启动期静态校验**（扫描 `@Transactional(assertion=MANDATORY)` 的方法，检查其调用链上是否存在事务边界）。

---

## RestorePoint（I4）

```java
/**
 * 连接原始状态快照。仅在最外层 begin 时捕获一次，最外层结束时恢复一次。
 * 嵌套层永不写入，故不存在现状 originalIsolation 单字段被覆盖的问题。
 */
public record RestorePoint(boolean autoCommit, int isolation, boolean readOnly) {

    static RestorePoint capture(Connection c) throws SQLException {
        return new RestorePoint(c.getAutoCommit(), c.getTransactionIsolation(), c.isReadOnly());
    }

    /** 逐项比对后再写，避免无谓的驱动往返。 */
    void restore(Connection c) throws SQLException {
        if (c.getAutoCommit() != autoCommit) c.setAutoCommit(autoCommit);
        if (c.getTransactionIsolation() != isolation) c.setTransactionIsolation(isolation);
        if (c.isReadOnly() != readOnly) c.setReadOnly(readOnly);
    }
}
```

注意 `readOnly`：现状 `readOnly` 走回滚路径却从不调 `Connection.setReadOnly(true)`（P2）。新设计真正设置它——PostgreSQL 可用 `SET TRANSACTION READ ONLY` 优化，MySQL 只读事务可跳过部分锁开销，且从库路由天然安全。

---

## 连接生命周期（I5 + I6）

```
Session 创建
   │
   ├─► dataSource.getConnection()        ← 借出一次，池已完成校验
   │   RestorePoint.capture(conn)        ← I4
   │   setAutoCommit(false) / 隔离 / readOnly
   │
   ├─► 事务期间所有 SQL 复用同一 Connection
   │   ★ 绝不 isValid()、绝不换连接（I5）
   │   连接真挂了 → SQLException 自然抛出 → 回滚路径 → 明确失败
   │
   └─► Session.close()（恰好一次，I6）
       RestorePoint.restore(conn)        ← 干净归还
       conn.close()                      ← 归还池
```

**为什么绝不校验**：连接池借出时已验证（Agroal / Hikari 均有 validation-on-borrow）。事务中校验有两个害处：每条 SQL 一次网络往返（P1-3 实测 100 次调用 99 次 ping），以及失效时静默换连接导致事务语义丢失。**连接在事务中挂掉必须响亮失败，不能悄悄续命。**

---

## 重试（新能力）

序列化失败与死锁自动重试是生产刚需，Spring 至今需用户自配 `@Retryable`。

```java
public interface RetryPolicy {
    boolean shouldRetry(SQLException e, int attempt);
    Duration backoff(int attempt);

    RetryPolicy NONE = ...;

    /** 40001 序列化失败 / 40P01 死锁 / MySQL 1213 / MySQL 1205 锁等待超时 */
    static RetryPolicy onSerializationFailure(int maxAttempts) { ... }
}
```

### 硬约束

**只有函数式形态可重试。** RAII 形态（`try (Tx tx = ...)`）的 body 不在框架掌控中，无法重放，`begin(TxOptions)` 遇到非 `NONE` 的 `retry` 直接抛 `IllegalArgumentException`。这个约束必须在 API 层强制，不能只写文档。

重试前必须完成回滚并**释放连接重新获取**——复用回滚后的连接在部分驱动上有残留状态风险。

---

## 可观测性（差异化点）

```java
public interface TxListener {
    default void onBegin(TxEvent e) {}
    default void onCommit(TxEvent e) {}
    default void onRollback(TxEvent e, Throwable cause) {}
    default void onRetry(TxEvent e, int attempt, SQLException cause) {}
    /** 连接借出超阈值未归还，携带借出时的栈快照 */
    default void onLeakSuspected(TxEvent e, Duration heldFor, StackTraceElement[] acquiredAt) {}
}

public record TxEvent(
        DataSourceId dataSource,
        Propagation propagation,
        Isolation isolation,
        boolean readOnly,
        int statementCount,
        int retryAttempt,
        Duration duration,
        TxState finalState
) {}
```

属性命名对齐 OTel DB 语义约定（`db.system`、`db.namespace`、`db.operation.name`），便于直接产出 span 而不需要适配层。

**连接泄漏检测**参考 HikariCP `leakDetectionThreshold`——这是该项目最受欢迎的功能之一，值得内建而非留给用户。

---

## 环境态：降级为糖，不是承重

显式句柄是主线。环境态只为 `@Transactional` 兼容层服务：

```java
interface TxContext {
    Optional<Tx> current();
    <R> R runWith(Tx tx, Callable<R> body) throws Exception;

    /** JDK 17：ThreadLocal 实现 */
    static TxContext threadLocal() { ... }
    /** JDK 21+：ScopedValue 实现（JEP 506 已于 JDK 25 正式） */
    static TxContext scopedValue() { ... }
}
```

因为句柄本身显式，这层只影响 `@Transactional` 的便利性，**不影响正确性**。这是关键区别：现状的正确性完全依赖环境态，故 Loom / 异步下必然崩；新设计中环境态失效最多导致 `AmbientPolicy.REQUIRE_TX` 报错——**响亮失败，不是静默错误**。
