# 08 用例驱动的 API 设计

> **本文与 `03-api.md` 的关系**：`03` 是**实现者视角**的完整接口契约（含内部类型）；本文是**用户视角**的 API 导航。
> 两者互补；若冲突，**用户可见 API 以本文为准**（`03` 接口签名偏实现细节，需按本文收敛）。

---

## 0. 设计方法：先写代码，再推 API

先列出使用者真实会写的场景，按频率排序，再反推最小 API 面。

**核心原则**：

1. **一个主入口**——`joya.tx(...)` 覆盖 90% 场景
2. **按需渐进**——其余能力都是 `TxOptions` 的可选字段，不出现在入门路径上
3. **默认即正确**——默认 `REQUIRED` + 默认隔离 + 非只读 + 不重试
4. **对内部概念零暴露**——用户不需要知道 `TxConnection` / `TxStatus` / `RestorePoint` 的存在

---

## 1. 五层 API

### 第 0 层：自动提交（不需要事务）

单条 SQL 本身就具备原子性，无需事务。

```java
Long id = joya.insert(user);
int rows = joya.update(user);
List<User> users = joya.queryList(stmt, User.class);
```

**这一层不是"事务 API"**，但必须明确它的语义是"非事务、自动提交"——与 `tx.update(...)` 形成对照，消灭"我以为在事务里"的模糊地带。

---

### 第 1 层：`joya.tx(...)` —— 覆盖 90%

多步操作需要原子时，**唯一需要记住的入口**。

```java
// 无返回值
joya.tx(tx -> {
    tx.insert(order);
    tx.update(account);
});

// 带返回值
Long orderId = joya.tx(tx -> {
    tx.insert(order);
    return order.getId();
});

// 只读（等价于 joya.txReadOnly，语义更明确）
List<Order> orders = joya.txReadOnly(tx ->
        tx.queryList(stmt, Order.class));
```

**这一层用户需要知道的只有两件事**：
- `joya.tx(lambda)` / `joya.txReadOnly(lambda)`
- `tx.xxx()` 的执行方法签名与第 0 层**完全一致**（学过 joya 就会用）

异常自动回滚，无需 `finally`；返回值自动透传。

---

### 第 2 层：`TxOptions` —— 覆盖 9%

**只在默认行为不够用时才出现**。全部通过语义化静态工厂提供，用户**不需要知道** `RetryPolicy` / `Propagation` 等接口的存在。

```java
// 语义化工厂 —— 命名即文档
joya.tx(TxOptions.serializable(), tx -> {...});        // 隔离级别
joya.tx(TxOptions.timeoutSeconds(5), tx -> {...});     // 超时
joya.tx(TxOptions.retryOnDeadlock(3), tx -> {...});    // 死锁重试
joya.tx(TxOptions.retryOnSerializationFailure(3), tx -> {...});
joya.tx(TxOptions.readOnly(), tx -> {...});            // 只读

// 组合
joya.tx(TxOptions.builder()
        .isolation(Isolation.SERIALIZABLE)
        .timeoutSeconds(5)
        .retryOnDeadlock(3)
        .build(),
    tx -> {...});
```

```java
public record TxOptions(...) {

    public static final TxOptions DEFAULT = ...;
    public static final TxOptions READ_ONLY = ...;
    public static final TxOptions REQUIRES_NEW = ...;

    // 语义化工厂：用户不必接触 RetryPolicy / Propagation 接口
    public static TxOptions serializable() { ... }
    public static TxOptions timeoutSeconds(int seconds) { ... }
    public static TxOptions retryOnDeadlock(int maxAttempts) { ... }
    public static TxOptions retryOnSerializationFailure(int maxAttempts) { ... }
    public static TxOptions readOnly() { ... }

    public static Builder builder() { ... }
}
```

---

### 第 3 层：`begin()` + RAII —— 覆盖 1%

事务需要**跨方法传递**时（函数式 lambda 的作用域不够用）。

```java
try (Tx tx = joya.begin()) {
    orderService.create(tx, order);      // tx 显式传入
    accountService.deduct(tx, account);
    tx.commit();
}   // 未 commit → close() 自动回滚
```

**使用规则（写死在文档里，不让用户纠结）**：

| 场景 | 用哪个 |
|---|---|
| 常规写操作 | 函数式 `joya.tx(...)` |
| 需要重试 | 函数式（**唯一支持**——lambda 可重放） |
| 跨方法传递 / 流式查询 | RAII `joya.begin()` |

规则明确，"选哪个"就不构成成本。

---

### 第 4 层：`unitOfWork()` —— 罕见

一个业务动作要**同时写多个库**。

```java
joya.unitOfWork(uow -> {
    uow.on(ORDERS).insert(order);
    uow.on(BILLING).update(invoice);
});
// 非原子：可能部分提交 → PartialCommitException（携带已提交列表）
```

**为什么独立成入口而非混进 `tx()`**：让"这不是原子的"在调用点显式可见。语义诚实 > 概念数量少。

---

### 特殊语义：独立事务

```java
joya.tx(tx -> {
    tx.insert(order);

    // 审计日志必须独立提交，不受外层回滚影响
    joya.tx(TxOptions.REQUIRES_NEW, tx2 -> {
        tx2.insert(auditLog);
    });

    // 此处抛异常 → order 回滚，auditLog 已提交
});
```

**注意**：这里**没有挂起栈**——外层 `tx` 是值，内层 `tx2` 是另一个值，作用域天然隔离。这正是"事务是值"范式相对 Spring 环境态的红利。

---

## 2. 用户只需 3 个概念

| # | 概念 | 何时学 |
|---|---|---|
| 1 | `joya.tx(tx -> {...})` | 第一天 |
| 2 | `tx.xxx()` —— SQL 执行 | 第一天（与第 0 层同签名，零新增） |
| 3 | `TxOptions.xxx()` —— 可选配置 | 按需 |

**对比之前评估的"12+ 概念"**：那次我把**框架内部类型**错当成了**用户学习成本**。修正如下：

| 类型 | 归属 | 用户是否接触 |
|---|---|---|
| `TxConnection`（原 `Session`） | 内部 | ✗ |
| `TxStatus`（原 `TxRuntime`） | 内部 | ✗ |
| `TxState` | 内部 | ✗ |
| `TxExecutor`（原 `TxRunner`） | 内部 | ✗ |
| `RestorePoint` | 内部 | ✗ |
| `RetryPolicy` | 内部（由 `TxOptions.retryOnXxx()` 包装） | ✗ |
| `Propagation` | 内部（由 `TxOptions.REQUIRES_NEW` 常量表达） | ✗ |
| `TxAssertion` | 仅 `@Transactional` 用 | 可选 |
| `TxSpan` / `TxListener` | 可观测扩展点 | 可选 |
| **`Tx` / `TxOptions` / `unitOfWork`** | **用户 API** | **✓** |

**内部复杂度不收钱，用户 API 才收钱。** 这是这轮最关键的认知修正。

---

## 3. 反推：内部需要什么（用户不接触）

从第 1 节的用户代码反推，实现侧最小集合：

| 内部类型 | 为什么必需 | 对应不变量 |
|---|---|---|
| `TxConnection` | 独占连接 + 状态机 + depth | I2 I3 I5 I6 I9 |
| `TxState`（**5 态**） | 显式状态，绝不从 `autoCommit` 反推 | I2 |
| `TxStatus` | 收敛 state/depth/rollbackOnly 为不可变快照 | I2 I3 I7 |
| `TxExecutor` | 异常合成 + 重试收敛点 | I8 |
| `RestorePoint` | 最外层捕获一次、恢复一次 | I4 |
| `ParticipantList`（`UnitOfWork` 内） | 保序容器：正序提交、逆序释放 | 多库 |
| `TransactionPlatform` SPI | 本地 / Spring / XA 三实现 | 分层 |

**`TxState` 收敛回 5 态**（覆盖 100% 核心路径）：

```java
public enum TxState {
    NONE, ACTIVE, MARKED_ROLLBACK, COMMITTED, ROLLED_BACK;
}
```

2PC 中间态（`PREPARING`/`PREPARED`/`COMMITTING`/`ROLLING_BACK`/`UNKNOWN`）**移出核心**——它们在有序 1PC 下永不执行，是死代码 + 认知负担；XA 插件需要时自己定义扩展状态机。

---

## 4. 与 `03-api.md` 的差异

| 项 | `03` | 本文 | 理由 |
|---|---|---|---|
| `Tx` 接口方法数 | 20+（含 `on(DataSourceId)`、`state()`、`enlisted()`） | 收敛：SQL 执行 + `commit/rollback/setRollbackOnly/savepoint` | 接口隔离；`on()` 属 `UnitOfWork` 职责，从 `Tx` 移除 |
| `Propagation` 暴露方式 | 枚举值直接传 | `TxOptions.REQUIRES_NEW` 常量 | 用户不必理解"传播"概念 |
| `RetryPolicy` 暴露方式 | 接口直接传 | `TxOptions.retryOnDeadlock(n)` 工厂 | 同上 |
| `TxState` | 5 态 | 5 态（**确认不变**） | 我曾扩到 10 态，是过度设计，已撤回 |
| `UnitOfWork` 提交序 | 有序（注册序） | 有序 + 逆序释放 | 事故可复盘；为 XA 预留 |
| 幂等键 | — | **不做独立 API** | 进程内去重覆盖重试场景；跨进程用业务唯一索引 |
| 框架自有表 | — | **零建表** | Joya 是数据访问库，不是有状态服务 |

---

## 5. 一页速查

```java
// 自动提交（非事务）
joya.insert(user);

// 事务 —— 90%
joya.tx(tx -> { tx.insert(order); tx.update(account); });
Long id = joya.tx(tx -> { tx.insert(order); return order.getId(); });

// 只读
joya.txReadOnly(tx -> tx.queryList(stmt, Order.class));

// 配置 —— 按需
joya.tx(TxOptions.serializable(), tx -> {...});
joya.tx(TxOptions.timeoutSeconds(5), tx -> {...});
joya.tx(TxOptions.retryOnDeadlock(3), tx -> {...});
joya.tx(TxOptions.builder().isolation(SERIALIZABLE).timeoutSeconds(5).build(), tx -> {...});

// 跨方法
try (Tx tx = joya.begin()) { serviceA(tx); serviceB(tx); tx.commit(); }

// 独立事务
joya.tx(TxOptions.REQUIRES_NEW, tx2 -> tx2.insert(auditLog));

// 跨库（非原子）
joya.unitOfWork(uow -> { uow.on(ORDERS).insert(o); uow.on(BILLING).update(i); });
```

**就这些。**
