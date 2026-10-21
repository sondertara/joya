# 06 最终落地方案

> 本文是 `01`–`05` 的**最终执行版**：`01`–`05` 的缺陷分析、九条不变量、API 契约与迁移清单**全部采纳**，本文补充现代化维度评估的结论，给出统一阶段计划与验收总表。
>
> 若与 `02`/`05` 冲突，**以本文为准**（本文记录了评估后对 `02` 部分设计的修正）。

---

## 1. 结论摘要

| 判断 | 结论 |
|---|---|
| `01`–`05` 的地基 | **全部采纳**。九条不变量、传播 3+4 收敛、`Session` 独占连接、有序 1PC，均直接实施 |
| 单库本地事务维度 | 原方案已接近完备，补全细节后即可落地 |
| 补偿事务（Saga） | **不做**。层次错位：Saga 是业务流程编排问题，不属于 ORM |
| TCC | **不做**。理由同上，且比 Saga 更侵入业务 |
| XA / 2PC | **保留为插件**，不进核心（原方案立场正确） |
| 真正要补的 | 统一 `TxContext` 抽象、状态机补 2PC 中间态、`TxLog` 可恢复、幂等键、结构化 span、跨服务传播契约 |

**核心原则：做少而做对。** Joya 守住"SQL 透明、零魔法"，把跨服务编排交给 Temporal / Camunda / MQ。Joya 的责任是**让可靠分布式系统成为可能**——本地事务绝对正确、部分提交可恢复、幂等有保障、`tx-id` 可传播。

---

## 2. 边界：明确不做

边界是设计的一部分。以下项经过评估后**明确排除**，避免日后反复争论。

| 不做 | 理由 |
|---|---|
| **Saga 引擎**（含 SagaLog 恢复、补偿编排） | ① 层次错位——Saga 是业务流程编排，非数据访问；② 与「SQL 透明、零魔法」定位冲突，此冲突比 Seata AT 更严重（`04` 拒绝 AT 的理由同样适用）；③ 复杂度无法被框架消除——补偿语义、幂等、隔离性（语义锁）仍需业务承担 |
| **TCC 接口预留** | 同上。且 TCC 要求业务写 Try/Confirm/Cancel 三套，侵入性高于 Saga |
| **XA 进核心** | 需外部 TM（Narayana/Atomikos）、2PC 额外网络往返、recovery 复杂、使用率低。保持插件形态 |
| **投递轮询器** | outbox 只提供表 + `publish()` API，投递交给 Debezium CDC 直连 binlog，零代码零轮询 |
| **Loom 只读并行查询** | 收益不明，且语义可疑（快照一致性依赖隔离级别），暂不做 |
| **`rollbackFor` 字符串数组** | 原方案立场：删除。字符串类名匹配无编译期检查 |

### 关于隔离性缺失（Saga 被拒的深层原因）

Saga 没有 ACID 中的 I，中间状态对外可见：

```
扣款成功 → 发货失败 → 补偿退款
                    ↑ 退款完成前，用户看到"钱扣了、货没发"
```

业界补丁是"语义锁"（订单加 `status=PROCESSING`），但该补丁自带死锁、超时、清理三件套。**框架无法消除这个复杂度，只能搬运它**——这就是 Saga 框架（区别于编排引擎）成功率不高的原因。

真正落地的 Saga 都在编排引擎里（Temporal / Camunda / Cadence），其本质是 **durable execution**（持久化工作流状态 + 确定性重放），是另一个品类，不是 ORM 插件。

---

## 3. 最终阶段计划

### 阶段 0：测试地基（不可跳过）

与 `05` 阶段 0 一致。新增一项：状态迁移矩阵测试脚手架。

**验收**：每个 P0/P1 缺陷有一个红灯测试；`ConnectionSpy` 支持 `borrowCount`/`closeCount`/`isValidCount`/`ops()`/借出栈。

---

### 阶段 1：核心类型 + 状态机

> 本阶段定型**类型结构与状态机**，后续阶段的签名都依赖它。此处偷懒 = 后面全面返工。

#### 1.1 统一 `TxContext` 抽象（新增，原方案缺失）

原方案 `Tx`（单库）与 `UnitOfWork`（跨库）是两套割裂类型，导致**单库迁移到跨库必须重写调用代码**——而这正是业务最常见成长路径。

```java
/**
 * 事务上下文统一抽象 —— 覆盖本地 / 分布式 / 嵌套三种形态。
 * Tx 与 UnitOfWork 均实现此接口，使上层代码与事务形态解耦。
 */
public interface TxContext extends AutoCloseable {
    /** 事务唯一标识，用于追踪、日志关联、跨服务传播。 */
    TxId id();

    TxState state();
    void setRollbackOnly();

    /** 本上下文登记的所有参与者。本地事务恒为 1 个。 */
    Set<DataSourceId> enlisted();

    /** 提交语义随实现不同：本地=直提；分布式=有序1PC/XA。 */
    void commit();
    void rollback();
}

public interface Tx extends TxContext { /* SQL 执行能力，见 03 */ }
public interface UnitOfWork extends TxContext { Tx on(DataSourceId id); }
```

**价值**：业务代码面向 `TxContext` 编写，单库 → 多库无缝迁移。

#### 1.2 `TxId` 与传播契约（P0，零成本）

```java
/**
 * 事务标识。设计为可传播：跨服务调用时通过 carrier 传递，
 * 使下游能关联、追踪、幂等去重。
 */
public record TxId(String value) {
    public static TxId random() { return new TxId(ULID.next()); }
}

/**
 * 跨服务传播。接口现在定义，实现可延后到阶段 4。
 * 与 W3C TraceContext 并存：优先独立 header，避免耦合 OTel。
 */
public interface TxContextPropagator {
    String CARRIER_KEY = "joya-tx-id";

    void inject(TxContext tx, Map<String, String> carrier);
    Optional<TxId> extract(Map<String, String> carrier);
}
```

**为什么 P0**：只定义接口和一个字段，成本近零；后加则协议已成型，改动面大。

#### 1.3 状态机：显式迁移表 + 2PC 中间态（原方案未覆盖）

原方案的状态机是"扁平枚举 + 手写 `requireState` 守卫"，合法迁移只存在于文档的状态图里。且**缺少分布式所需的中间态**。

```java
public enum TxState {
    NONE, ACTIVE, MARKED_ROLLBACK,
    PREPARING, PREPARED, COMMITTING, ROLLING_BACK,   // ← 新增：2PC / 长事务可观测
    COMMITTED, ROLLED_BACK,
    UNKNOWN;                                          // ← 新增：2PC 不确定态，需外部裁决

    private static final Map<TxState, Set<TxState>> TRANSITIONS = Map.of(
        NONE,            EnumSet.of(ACTIVE),
        ACTIVE,          EnumSet.of(ACTIVE, MARKED_ROLLBACK, PREPARING, COMMITTING, ROLLING_BACK),
        MARKED_ROLLBACK, EnumSet.of(ROLLING_BACK, ROLLED_BACK, UNKNOWN),
        PREPARING,       EnumSet.of(PREPARED, ROLLING_BACK, UNKNOWN),
        PREPARED,        EnumSet.of(COMMITTING, ROLLING_BACK, UNKNOWN),
        COMMITTING,      EnumSet.of(COMMITTED, UNKNOWN),
        ROLLING_BACK,    EnumSet.of(ROLLED_BACK, UNKNOWN),
        UNKNOWN,         EnumSet.of(COMMITTED, ROLLED_BACK)     // 恢复流程裁决
    );

    /** 唯一合法迁移来源：代码与文档同源。 */
    public void assertTransitionTo(TxState target) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(target)) {
            throw new IllegalTxStateException(this, target, TRANSITIONS.get(this));
        }
    }
}
```

新增中间态解决的具体问题：
- `COMMITTING` 让你能区分"正在提交"和"卡住了"（原方案无法区分）
- `UNKNOWN` 是 2PC 的诚实表达：协调者崩溃后参与者不知该提交还是回滚，必须由恢复流程裁决
- `PREPARING`/`PREPARED` 为 XA 插件预留

#### 1.4 `TxRuntime`：收敛状态字段（消除不一致）

原方案 `state` / `depth` / `globalRollbackOnly` 是三个独立可变字段，存在不一致窗口。

```java
/**
 * 事务运行时状态 —— 不可变快照，每次变更返回新实例。
 * 消除三字段不一致的可能。
 */
public record TxRuntime(
        TxState state,
        int depth,
        boolean globalRollbackOnly,
        long startedAtNanos,
        int statementCount
) {
    public TxRuntime enter() { return new TxRuntime(state, depth + 1, globalRollbackOnly, startedAtNanos, statementCount); }
    public TxRuntime exit()  { return new TxRuntime(state, depth - 1, globalRollbackOnly, startedAtNanos, statementCount); }
    public TxRuntime markRollbackOnly() { return new TxRuntime(state, depth, true, startedAtNanos, statementCount); }

    /** I3：物理操作仅最外层。 */
    public boolean isOutermost() { return depth == 0; }
}
```

#### 1.5 其余（沿用 `05` 阶段 1）

`Tx` / `TxImpl` / `TxOptions` / `Propagation`(3值) / `TxAssertion`(4值) / `Isolation`(修正版) / `Session` / `RestorePoint` / `TxCapabilities`。

**本阶段验收**
- `05` 阶段 1 全部验收项（P0-1/P0-3/P1-1/P1-2/P1-3 红灯转绿）
- **新增**：状态机非法迁移矩阵测试（每个非法迁移抛 `IllegalTxStateException`，错误信息含"当前态/目标态/合法迁移集"）
- **新增**：`Tx` 与 `UnitOfWork` 均为 `TxContext` 子类型的编译期断言
- **新增**：`autoCommit=false` 池下全部传播行为正确

---

### 阶段 2：执行 + 重试 + 幂等 + 可观测

#### 2.1 `TxRunner`（沿用 `05` 阶段 2）

异常合成 I8：业务异常胜出，清理失败一律 `addSuppressed`。

#### 2.2 幂等键（**新增，P1**）

原方案的 `RetryPolicy` 要求"回调必须幂等"，但**只是文档纪律，框架未提供任何支撑**。这是重试从"安全"变"危险"的缺口。

```java
/**
 * 幂等键 —— 让重试可安全进行。
 *
 * <p>实现：登记记录与业务操作在<b>同一本地事务</b>写入，依赖唯一索引冲突判重。
 * 业务回滚 → 登记一并回滚 → 重试天然安全。这正是幂等键应由事务层提供的原因：
 * <b>它依赖本地事务的原子性</b>。
 */
public record IdempotencyKey(String value) {
    public static IdempotencyKey of(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
        return new IdempotencyKey(value);
    }
}
```

```java
joya.tx(TxOptions.builder()
        .retry(RetryPolicy.onSerializationFailure(3))
        .idempotencyKey(IdempotencyKey.of("order:" + orderId))
        .build(), tx -> {
    tx.insertInto(Order.class).values(order).execute();
});
```

语义选择：**只做去重，不做结果重放**。重复请求抛 `DuplicateRequestException`（含首次执行的 `tx-id`），由调用方查询结果。避免序列化结果带来的版本兼容问题，符合"零魔法"定位。

存储：默认 `joya_idempotency` 表（框架提供 DDL），可配置到业务库。

#### 2.3 可观测性：结构化 `TxSpan`（替代平铺回调）

原方案 `TxListener` 的 `onBegin/onCommit/onRollback` 是三个独立回调，**无法表达嵌套事务的父子关系，也无法表达重试产生的多次尝试**。

```java
/**
 * 事务 span —— 结构化、有父子关系，可直接映射 OTel Span。
 */
public interface TxSpan extends AutoCloseable {
    TxSpan child(String name);                  // 嵌套事务 / 分布式参与者
    TxSpan attribute(String key, Object value);
    void event(String name);                     // 瞬时事件（savepoint、重试）
    void recordException(Throwable t);
    void end(TxState finalState);
}

public interface TxListener {
    default void onBegin(TxSpan span) {}
    default void onEnd(TxSpan span, TxState finalState) {}
    /** 事务运行超阈值（区别于连接持有超时）→ 持锁阻塞他人，生产事故高频源头。 */
    default void onLongRunningTx(TxSpan span, Duration elapsed, StackTraceElement[] txStartStack) {}
    default void onLeakSuspected(TxSpan span, Duration heldFor, StackTraceElement[] acquiredAt) {}
}
```

属性命名对齐 OTel DB 语义约定（沿用 `02`）。

#### 2.4 错误信息规范（P1，排障效率量级差异）

```java
// 所有 TxException 必须携带完整上下文
throw new IllegalTxStateException(
        "Cannot commit transaction",
        TxErrorContext.of(txId, currentState, expectedStates, dataSourceId, txStartStack)
);
```

强制包含：`tx-id`、当前状态、期望状态集、数据源、事务开始栈。

**本阶段验收**
- `05` 阶段 2 全部验收项
- **新增**：幂等键去重测试（重复 key → `DuplicateRequestException`；回滚后重试 → 成功）
- **新增**：span 父子关系断言（嵌套事务的 span 是外层 span 的 child）
- **新增**：长事务超阈值触发 `onLongRunningTx`
- **新增**：异常信息包含 `tx-id` 与状态上下文

---

### 阶段 3：并发控制 + 多数据源 + 路由

#### 3.1 乐观并发控制（新增，P1）

原方案把并发控制完全委托给数据库隔离级别，无应用层乐观锁。

```java
@Version
private Long version;

// UpdateStatementFactory 自动生成：
// UPDATE t SET ..., version = version + 1 WHERE id = ? AND version = ?
// 影响行数 0 → OptimisticLockException
```

`OptimisticLockException` 必须归入**不可自动重试**分类（重试乐观锁冲突无意义）。

#### 3.2 隔离级别禁止静默降级（新增，P1）

请求不支持的隔离级别时驱动会静默降级（MySQL 请求 SNAPSHOT 实际给 REPEATABLE READ）——**静默降级是数据正确性事故**。

```java
if (!caps.supportedIsolations().contains(options.isolation())) {
    throw new UnsupportedIsolationException(dsId, requested, caps.supportedIsolations());
}
```

宁可启动失败，不要运行时静默降级。这是"响亮失败"哲学在隔离级别上的一致应用。

#### 3.3 超时分层（新增，P2）

原方案 `TxOptions.timeout` 只有一个值。现代化框架区分：

```java
public record TxOptions(
        // ... 原有字段
        Duration lockTimeout,       // 等锁超时 → DB 侧（SET lock_timeout）
        Duration statementTimeout,  // 单语句超时 → Statement.setQueryTimeout
        Duration txTimeout          // 整体超时 → 事务守护
) {}
```

#### 3.4 多数据源（沿用 `05` 阶段 3）

`DataSourceId` / `DataSourceRegistry` / `RoutingStrategy`（begin 时解析一次并钉死）/ `SqlInterceptor.mayChangePhysicalDatabase()` 链上校验 / `readOnly` 真正 `setReadOnly(true)` / 移除 `@ConditionalOnSingleCandidate`。

#### 3.5 启动期静态校验（新增，P2）

把运行时错误前移到启动期：
- `@Transactional(assertion = MANDATORY)` 的方法调用链上无事务边界 → 启动失败
- `mayChangePhysicalDatabase() == true` 的拦截器存在事务用法 → 启动警告

**本阶段验收**
- `05` 阶段 3 全部验收项
- **新增**：乐观锁冲突测试、乐观锁不重试测试
- **新增**：请求不支持隔离级别 → `UnsupportedIsolationException`
- **新增**：三类超时独立生效

---

### 阶段 4：分布式 + 可恢复 + Spring 互操作

#### 4.1 `UnitOfWork` 有序 1PC（沿用 `05` 阶段 4）

三阶段协议（预检 → 有序提交 → 释放）、`PartialCommitException` 携带已提交列表。

#### 4.2 `TxLog` 持久化（新增，P1 —— 一切补偿的前提）

原方案 `UnitOfWork` 提交到一半崩溃后，**重启无从得知哪些库已提交**。`PartialCommitException` 抛给用户，但用户进程也可能崩。

```java
/**
 * 事务日志 —— 让"部分提交"从"诚实报告"升级为"可恢复"。
 */
public interface TxLog {
    /** 提交前持久化意图（WAL 语义）。 */
    void beforeCommit(TxId id, List<DataSourceId> enlisted);
    /** 每个参与者提交后记录。 */
    void afterParticipantCommit(TxId id, DataSourceId ds);
    /** 全部完成后清理。 */
    void complete(TxId id);
    /** 启动时扫描未完成记录 → 进入恢复流程。 */
    List<TxRecoveryRecord> pendingRecoveries();
}
```

#### 4.3 传播实现 + 指标 + 连接策略（P2）

- `TxContextPropagator` 默认实现（HTTP header / MQ property）
- `TxMetrics`：begin/commit/rollback/retry/connectionWait 计数与直方图，提供 Micrometer / OTel 适配器，无依赖时 no-op
- `ConnectionAcquirer` 策略化（默认直连池，可扩展超时/熔断/优先级）
- 读后写升级 `Tx.upgradeToWrite()`（抛异常若已有写语句）

#### 4.4 Spring 互操作（沿用 `05` 阶段 4）

`SpringTxPlatform`：`ownsTransaction = false` 的 Session 不发物理 COMMIT，回滚意图通过 `TransactionAspectSupport` 传给 Spring。

**本阶段验收**
- `05` 阶段 4 全部验收项（含 Spring 同连接测试）
- **新增**：`TxLog` 崩溃恢复测试（模拟阶段 2 崩溃 → 重启 → `pendingRecoveries()` 返回中间态记录 → 恢复流程裁决）
- **新增**：传播契约测试（inject → extract 往返一致，跨线程/跨服务 carrier 传递）

---

### 阶段 5：迁移 + 清理

沿用 `05` 阶段 5 删除清单与调用点迁移，新增：

#### 5.1 拆分 `Tx` 接口（P2，接口隔离）

原 `Tx` 有 20+ 方法，混合生命周期、savepoint、SQL 执行、Builder 派生、多数据源切换，导致 mock/装饰成本高。

```java
public interface Tx extends TxContext, SqlExecutor {
    <T> JoyaMapper<T> mapper(Class<T> entityType);
    <T> InsertBuilder<T> insertInto(Class<T> t);
    <T> UpdateBuilder<T> update(Class<T> t);
    <T> DeleteBuilder<T> deleteFrom(Class<T> t);
}

/** 与 JoyaTemplate 共享签名 —— 自动提交路径与事务路径同构。 */
public interface SqlExecutor {
    <T> T query(SqlStatement s, RowMapper<T> m);
    <T> List<T> queryList(SqlStatement s, RowMapper<T> m);
    <T> Stream<T> queryStream(SqlStatement s, RowMapper<T> m);
    int update(SqlStatement s);
    Object insert(SqlStatement s);
    int[] batchUpdate(BatchSqlStatement batch);
    <T> PageResult<T> queryPage(SqlStatement s, RowMapper<T> m, PageParam page);
}
```

#### 5.2 文档同步

修正 `AGENTS.md` 中已不存在的类（`SyncManager` / `TransactionObject` / `TransactionTemplateManager` / `LocalTransactionStatus` / `SimpleJoyaMapper`），替换「关键架构决策」1–3 条为九条不变量 + 本文边界清单。

---

### 阶段 6（可选，P3，可砍）：outbox 轻量支持

```java
joya.tx(tx -> {
    tx.insertInto(Order.class).values(order).execute();
    tx.outbox().publish(new OrderCreated(order.getId()));   // 同一本地事务落库
});
```

**范围严格限定**：提供 outbox 表 DDL + `publish()` API。**投递不实现**——交给 Debezium CDC 直连 binlog，零代码、零轮询器、零魔法。

价值：把"分布式事务"降级为"本地事务 + 消息可靠投递"。这是业界共识（eBay 2008 论文，Debezium + Kafka 生态成熟）。

---

## 4. 优先级矩阵汇总

| 优先级 | 能力 | 维度 | 阶段 | 状态 |
|---|---|---|---|---|
| **P0** | 九条不变量（I1–I9） | 模型/状态 | 1 | 沿用原方案 |
| **P0** | `TxContext` 统一抽象 | 模型 | 1 | **本次新增** |
| **P0** | 状态迁移表显式化 + 2PC 中间态 | 状态机 | 1 | **本次新增** |
| **P0** | `TxId` + 传播契约 | 跨服务 | 1 | **本次新增** |
| **P1** | `TxRuntime` 收敛状态字段 | 状态机 | 1 | **本次新增** |
| **P1** | 幂等键 | 并发 | 2 | **本次新增**（原评估误标 P3） |
| **P1** | 结构化 `TxSpan` | 可观测 | 2 | **本次新增** |
| **P1** | 长事务检测 | 可观测 | 2 | **本次新增** |
| **P1** | 错误信息规范 | DX | 2 | **本次新增** |
| **P1** | 乐观锁 `@Version` | 并发 | 3 | **本次新增** |
| **P1** | 隔离级别禁止降级 | 并发 | 3 | **本次新增** |
| **P1** | `TxLog` 可恢复 | 状态机 | 4 | **本次新增** |
| **P2** | 超时分层 | 并发 | 3 | **本次新增** |
| **P2** | 启动期静态校验 | DX | 3 | **本次新增** |
| **P2** | 指标导出 | 可观测 | 4 | **本次新增** |
| **P2** | 连接获取策略化 / 读后写升级 | 性能 | 4 | **本次新增** |
| **P2** | 拆分 `Tx` 接口 | DX | 5 | **本次新增** |
| **P3** | outbox（表 + API） | 跨服务 | 6 | 可砍 |
| — | Saga 引擎 | — | — | **明确不做** |
| — | TCC | — | — | **明确不做** |

---

## 5. 验收总表（阶段 5 完成时必须全绿）

在 `05` 测试矩阵基础上新增：

| 类别 | 断言 |
|---|---|
| 状态机 | 每个非法迁移抛异常，错误信息含当前态/目标态/合法迁移集 |
| 状态机 | `COMMITTING` 态可被观测区分于"卡住" |
| 统一抽象 | `Tx` 与 `UnitOfWork` 均为 `TxContext` 子类型；面向 `TxContext` 的代码在单库/多库下均可运行 |
| 幂等 | 重复 key → `DuplicateRequestException`；回滚后同 key 重试 → 成功 |
| 乐观锁 | 版本冲突 → `OptimisticLockException`，且**不**触发重试 |
| 隔离 | 请求不支持的隔离级别 → `UnsupportedIsolationException`（非静默降级） |
| 可观测 | 嵌套事务 span 是外层 span 的 child；重试产生独立 attempt 事件 |
| 可恢复 | 模拟提交中崩溃 → 重启后 `pendingRecoveries()` 返回中间态记录 |
| 传播 | `inject` → `extract` 往返一致；跨线程 carrier 传递成功 |
| 错误信息 | 所有 `TxException` 携带 `tx-id` 与状态上下文 |

---

## 6. 相对 `02`/`05` 的修正记录

| 项 | 原方案 | 本文 | 理由 |
|---|---|---|---|
| 补偿事务 | 未涉及（仅 `PartialCommitHandler` 回调） | **明确不做 Saga / TCC** | 层次错位；复杂度无法被框架消除；与"零魔法"定位冲突 |
| `Tx` / `UnitOfWork` | 两套独立类型 | 统一 `TxContext` 父接口 | 单库 → 多库迁移不应重写调用代码 |
| 状态机 | 扁平枚举 + 手写守卫 | 显式迁移表 + 2PC 中间态 | 迁移规则应与代码同源；缺 `COMMITTING`/`UNKNOWN` 无法支撑分布式与可观测 |
| 状态字段 | `state`/`depth`/`globalRollbackOnly` 三独立字段 | `TxRuntime` 不可变记录 | 消除不一致窗口 |
| 重试幂等 | 文档纪律 | 幂等键内建 | 重试要求幂等却不提供支撑 = 把风险转嫁给用户 |
| `UnitOfWork` 崩溃 | `PartialCommitException` 抛给用户 | + `TxLog` 持久化 | 用户进程也可能崩；可恢复是一切补偿的前提 |
| 可观测 | 平铺回调 `onBegin/onCommit/onRollback` | 结构化 `TxSpan` | 回调无法表达嵌套父子关系与重试多次尝试 |
| 跨服务 | 未涉及 | `TxId` + `TxContextPropagator`（接口 P0，实现 P2） | 接口现在定义零成本，后加协议已成型 |
