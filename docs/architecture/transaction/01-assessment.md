# 01 现状评估

> 所有缺陷均已用可执行探针在**仓库编译产物**上复现，非代码走读推测。

## 复现方法

探针用 JDK 动态代理伪造 `Connection` / `DataSource`，记录 `setAutoCommit` / `commit` / `rollback` / `close` / `setTransactionIsolation` 的真实调用序列，直接驱动 `joya-core/target/classes` 里编译好的 `LocalTransactionManager`，**无需真实数据库**。

```bash
CP="joya-core/target/classes:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar"
javac -cp "$CP" -d /tmp/txprobe/out /tmp/txprobe/TxProbe*.java
for p in TxProbe TxProbe2 TxProbe3 TxProbe4; do java -cp "$CP:/tmp/txprobe/out" $p; done
```

> 探针源码见 [05 落地路线](05-migration.md#附探针代码归档) —— 重构后应转为正式回归测试。

---

## P0-1 嵌套事务摧毁外层事务（静默数据落库）

**严重度：最高。不抛异常，数据以不受控的隐式提交落库，回滚能力完全丧失。**

### 根因

`LocalTransactionManager.cleanupAfterCompletion` 无条件调用 `ctx.endTransaction()`：

```
LocalTransactionManager.java:339   ctx.endTransaction();
ConnectionContext.java:144-149     if (connection != null && !connection.getAutoCommit()) {
                                       connection.setAutoCommit(true);   // ← 隐式提交外层事务
                                   }
```

`AbstractTransactionStatus.isNewConnection()` 存在（`:80-82`），`commit()` / `rollback()` 里也用它守卫了 `doCommit` / `doRollback`（`:133`、`:156`），但 **cleanup 路径没有用它守卫 `endTransaction()`**。

### 实测输出（NESTED）

```
1) 外层 REQUIRED begin      ops = [setAutoCommit(false)]
                            hasTransaction=true   refCount=1
2) 内层 NESTED begin        ops = [setAutoCommit(false), SAVEPOINT]
                            hasTransaction=true   refCount=2
3) 内层 rollback 之后 ★     ops = [..., ROLLBACK_TO_SP, setAutoCommit(true)]
                            hasTransaction=false  ← 外层事务已死
4) 外层 commit 之后         ops = [..., setAutoCommit(true), CLOSE→归还池]
                            外层 COMMIT 是否发出 = false
```

第 3 步 `setAutoCommit(true)` 把外层的物理事务隐式提交了。第 4 步的 `commit(outer)` 变成空操作——因为 `doCommit` 的 `ctx.hasTransaction()` 守卫（`:260`）已经是 `false`。

### 实测输出（REQUIRED 嵌套）

```
2) 内层 REQUIRED begin（应加入外层）  ops = [setAutoCommit(false)]         refCount=2
3) 内层 commit 之后 ★                ops = [setAutoCommit(false), setAutoCommit(true)]
                                     hasTransaction=false
```

内层 `commit` 后 `setAutoCommit(true)`，外层剩余语句在**自动提交模式下裸跑**——每条语句独立提交，无法回滚。

---

## P0-2 单例 manager 的事务栈被多线程共享

**严重度：最高。跨线程物理提交他人事务 + 从非所有者线程操作 `Connection`。**

### 根因

```
LocalTransactionManager.java:58    private final Deque<AbstractTransactionStatus> tStatusStack
                                       = new LinkedBlockingDeque<>();   // ← 实例字段，非 ThreadLocal
JoyaJdbc.java:116                  this.transactionManager = new LocalTransactionManager(dataSource);
JoyaJdbcAutoConfiguration.java:42  @Bean public JoyaJdbc joyaJdbc(DataSource ds)   // ← 单例
```

`unwindTo`（`:201-213`）会把栈顶**别人的**事务替你提交/回滚掉：

```java
while (tStatusStack.peek() != target) {
    TransactionStatus top = tStatusStack.peek();
    if (top.isReadOnly() || top.isRollbackOnly()) rollback(top); else commit(top);
}
```

### 实测输出

```
线程A 异常 = null
线程B 异常 = SQLException: Transaction is already completed – do not call commit or rollback more than once
借出连接数 = 2
```

线程 A 的 `commit` 通过 `unwindTo` 把线程 B 在飞的事务**物理提交**了。同时踩两个雷：

1. 事务栈跨线程串扰
2. 从线程 A 调用线程 B 的 `Connection.commit()` —— JDBC `Connection` 非线程安全

### 附带发现：注释与事实不符

```
JoyaTransactionScope.java:16   替代 ThreadLocal<Deque> 隐式栈，避免线程池复用导致的连接泄漏
```

事实：`Deque` 仍在（从 `ThreadLocal` 变成了更糟的实例字段），`ThreadLocal` 也仍在（`TransactionSynchronization.java:40-41`）。注释是愿望，不是实现。

---

## P0-3 `hasTransaction()` 靠 `autoCommit` 反推 → 传播行为全线误判

**严重度：最高。连接池配 `autoCommit=false`（Agroal / HikariCP 均支持）时全线错乱。**

### 根因

```
ConnectionContext.java:121-128   public boolean hasTransaction() {
                                     if (connection == null) return false;
                                     return !connection.getAutoCommit();   // ← 从 autoCommit 反推
                                 }
```

`autoCommit=false` 并不意味着"存在受管事务"——它可能只是池的出厂配置。

### 实测输出（池 `autoCommit=false`）

```
NEVER（当前确实无事务）    : 抛异常 → existing transaction found for propagation 'NEVER'
MANDATORY（当前确实无事务）: 通过（错误：本应抛 no existing transaction）
NESTED（首个事务）         : ops = [SAVEPOINT]（错误：无事务却直接建 savepoint）
```

### 连带效应：事务活跃期间物理关闭连接

`beginTransaction()` 只在原本 `autoCommit=true` 时才置 `managedTransaction=true`：

```
ConnectionContext.java:133-139   if (connection.getAutoCommit()) {
                                     connection.setAutoCommit(false);
                                     managedTransaction = true;      // ← autoCommit=false 池下永不置位
                                 }
```

于是 `release()`（`:98-106`）认为"不在事务中"，直接物理 close：

```
begin 后 refCount=1  hasTransaction=true
一次事务内查询后 refCount=0  连接已物理关闭=true   ops=[CLOSE]
>>> 事务尚未提交，承载事务的物理连接已被 close() → 已执行语句被池回滚，且无异常
```

---

## P1-1 隔离级别错误恢复 → 污染连接池

### 根因

`originalIsolation` 是**单个字段**，嵌套 begin 覆盖外层记录的原值：

```
ConnectionContext.java:45             private Isolation originalIsolation;
LocalTransactionManager.java:228-240  写入（每层 begin 都写）
LocalTransactionManager.java:335-338  恢复（每层 cleanup 都恢复）
```

### 实测输出

```
池初始隔离 = REPEATABLE_READ
outer(SERIALIZABLE)   → SERIALIZABLE
inner(READ_COMMITTED) → READ_COMMITTED
inner 结束            → SERIALIZABLE          ← 恢复成了外层值，不是原始值
outer 结束            → SERIALIZABLE          （期望 REPEATABLE_READ）

ops = [isolation=SERIALIZABLE, setAutoCommit(false), isolation=READ_COMMITTED,
       isolation=SERIALIZABLE, setAutoCommit(true), isolation=SERIALIZABLE, CLOSE]
```

连接带着 `SERIALIZABLE` 归还池，**污染后续所有请求**。

### 附带：`Isolation` 枚举两个陷阱

```
Isolation.java:34      DEFAULT(-1)   // getValue() 返回 -1，仅靠调用方自觉避开 setTransactionIsolation
Isolation.java:88-95   fromJdbcValue(0) → IllegalArgumentException: Unknown JDBC isolation level: 0
```

实测：驱动上报 `Connection.TRANSACTION_NONE`（值为 `0`）时 `fromJdbcValue` 直接抛异常。类注释（`:24-26`）说明了不映射 `TRANSACTION_NONE` 的理由，但没有处理驱动**实际返回** `0` 的情况。

---

## P1-2 引用计数不对称

### 根因

查询路径取连接**不 retain**，`finally` 却 **release**：

```
JdbcExecutor.java:76-78    return TransactionSynchronization.getOrCreate(ds).getConnection();  // 无 retain
JdbcExecutor.java:89       TransactionSynchronization.release(dataSource);                      // 有 release
JoyaJdbc.java:456-458      同上
JoyaJdbc.java:471-487      doInTransaction 在事务内显式 release
```

### 实测输出

```
begin 后 refCount = 1
一次事务内查询后 refCount = 0        ← 事务仍活跃
第二次查询后 refCount = 0，binding 仍存在 = true
```

目前靠 `hasTransaction()` 兜底不 close，而 `hasTransaction()` 本身就是 P0-3 那个不可靠的推断。**两个 bug 互相掩护**——修好任何一个，另一个立刻暴露为连接泄漏或事务中断连。

---

## P1-3 每条 SQL 一次驱动 ping + 事务中静默换连接

### 根因

```
ConnectionContext.java:56-79   每次 getConnection() 都 isClosed() + isValid(10)，
                               失效则 dataSource.getConnection() 换新连接
```

### 实测输出

```
100 次 getConnection() → isValid 调用 = 99
```

MySQL 是一个 PING 包，PostgreSQL 是一次空查询——**每条 SQL 一次额外网络往返**。连接池（Agroal）在借出时已经验证过，这层是纯重复成本。

更糟的是失效时静默换连接：

```
事务开启于 Conn#1，autoCommit=false
再次取连接 → Conn#2，autoCommit=true
hasTransaction() = false
新连接 commit 次数 = 0，旧连接 commit 次数 = 0
>>> 事务语义丢失且无异常抛出
```

---

## P1-4 `finally { commit }` 吞掉根因

### 根因

```
TransactionTemplateImpl.java:46-50    } finally {
                                          if (!status.isCompleted()) transactionManager.commit(status);
                                      }
TransactionalInterceptor.java:119-123  同一模式
```

`finally` 块中抛出的异常会**替换**业务异常，且未使用 `addSuppressed`。

### 实测输出

```
场景：业务抛 IllegalStateException("余额不足") + rollback 在 DB 侧失败
最终抛出 = SQLException: rollback failed: connection reset
suppressed = []
```

排障时**既看不到业务根因，也看不到连接层根因的完整链路**。

---

## P2 结构与死代码

| 项 | 证据 |
|---|---|
| `@Transactional` 声明式事务**完全未生效** | `TransactionalInterceptor` 全仓零引用；`JdkMapperProxy` 已取代 `ByteBuddyMapperProxy`（`JoyaMapperProxyFactory.java:23`、`JoyaSession.java:23`），未织入任何拦截器 |
| 三处独立 manager 实例 | `JoyaJdbc.java:116`、`JoyaTemplate.java:59`、`TransactionalInterceptor.java:107`（**每次调用 new**）—— 三个独立事务栈操作同一个线程绑定连接 |
| `readOnly` 语义错误 | 走回滚路径（`LocalTransactionManager.java:122-128`），从不调 `Connection.setReadOnly(true)`，数据库端无从优化 |
| `TransactionScope` 无承重 | `kernel/tx/TransactionScope.java` 是正确的 RAII 直觉，但不承载执行能力；SQL 仍经 `TransactionSynchronization` 环境绑定走（`JdbcExecutor.java:77`），它只是 begin/commit 的遥控器 |
| 零测试 | 全仓无 `src/test`；`joya-core/pom.xml` 仅有 junit-jupiter 依赖，无嵌入式数据库 |
| Spring 事务不互通 | joya 的 `TransactionSynchronization`（`:40-41`）与 Spring 的 `TransactionSynchronizationManager` / `DataSourceUtils` 是两套并行的线程局部连接体系，互不可见 |
| 文档漂移 | `AGENTS.md` 列出的 `SyncManager` / `TransactionObject` / `TransactionTemplateManager` / `LocalTransactionStatus` / `SimpleJoyaMapper` 均不存在（实测 find 结果为 0） |

### Spring 事务不互通的实际后果（P0 级）

用户在 Spring `@Transactional` 方法里调 joya mapper：

```
Spring 已开事务，持有连接 C1（绑定在 TransactionSynchronizationManager）
joya 看不见 C1 → 自己从池另借 C2 → 再开一个事务
→ 同一逻辑工作单元跑在两个物理事务里
→ Spring 回滚不影响 joya 已提交的部分
```

---

## 顶层设计 vs 主流趋势

### 主流已换范式：事务是**你持有的值**

| 生态 | 事务句柄 | 环境态 |
|---|---|---|
| Go `database/sql` | `tx, _ := db.BeginTx(ctx, opts)`；查询必须走 `tx.Query` | 无 |
| Rust `sqlx` / `SeaORM` | `&mut Transaction<'_, DB>`；借用检查器保证不可能在 tx 外执行 tx 内语句 | 无 |
| jOOQ | `dsl.transactionResult(cfg -> cfg.dsl()...)` 传派生 `Configuration` | 无 |
| JDBI | `handle.inTransaction(h -> ...)` | 无 |
| .NET | `DbTransaction` 显式传递；`TransactionScope` 环境态在 async 下被公认为设计失误 | 遗留 |
| Kotlin Exposed | `transaction { }` + coroutine context | 协程作用域 |
| Spring | `ThreadLocal` + `DataSourceUtils` | **全靠环境态** |

Joya 现在站在最后一行。

### 为什么环境态是死路

1. **Loom 结构化并发**：`StructuredTaskScope` fork 的子任务继承线程局部语义，子任务拿到父任务的 `Connection` 并发使用 → JDBC `Connection` 非线程安全 → 静默数据错乱。必须能**检测**，不能靠"没人会这么写"。
2. **`ScopedValue`（JEP 506，JDK 25 正式）**：`ThreadLocal` 的继任者是不可变、有界作用域的。但迁到 `ScopedValue` 只解决泄漏，**不解决"环境态里到底有没有事务"的语义模糊**——P0-3 那类 bug 换个载体照样存在。
3. **异步 / 反应式**：一旦跨线程，线程亲和性归零。当前 joya-core 主干无异步代码（无 `CompletableFuture` / `ExecutorService`），所以现在没暴露——但任何用户在 `@Async` 里调 mapper 就静默失去事务。

### 从 Spring 继承的历史包袱

| 包袱 | 现代做法 |
|---|---|
| 靠切换 `autoCommit` 表达事务边界 | 显式状态机；连接以确定状态借出、以确定状态归还，绝不推断 |
| 7 值传播枚举 | 运行时只需 3 值；其余 4 个是**断言**，属于启动期/入口校验 |
| 异常类型驱动回滚（`rollbackFor` 字符串数组） | 默认"任何异常逃出即回滚"，`noRollbackFor` 作为窄逃生舱 |
| 无重试语义 | 序列化失败/死锁自动重试是生产刚需，Spring 至今要用户自配 `@Retryable` |
| 无可观测性契约 | OTel DB span 语义约定 + 连接泄漏检测（HikariCP `leakDetectionThreshold` 是最受欢迎的功能之一） |
| 环境态 + 代理 = 自调用失效 | 显式句柄天然没有 self-invocation 问题 |

---

## 多数据源现状

**不存在多数据源支持，且 starter 主动拒绝多数据源。**

| 维度 | 现状 | 证据 |
|---|---|---|
| 注册中心 | 无。`getName()` 默认恒返回 `"default"` | `DataSourceFactory.java:14-16` |
| starter 装配 | `@ConditionalOnSingleCandidate(DataSource.class)` —— 容器里有多个 `DataSource` 时**整个自动配置失效** | `JoyaJdbcAutoConfiguration.java:33` |
| 路由 / 读写分离 / 分片 | 无 `AbstractRoutingDataSource` 类物。仅 `kernel/interceptor/example/ShardingInterceptor.java` 示例，在 SQL 改写层，与连接选择、事务绑定完全脱钩 | — |
| 跨库事务 | 无 | — |
| XA 通道 | Agroal 的 `TransactionAware` / `TransactionIntegration` / `XAConnectionWrapper` / `XAResourceWrapper` 代码完整存在，但默认 `none()` + `transactionRequirement = OFF`，`JoyaDataSource` 走 DRIVER 模式。**整条 XA 管线休眠，joya 自身从未实现 `TransactionIntegration`** | `AgroalConnectionPoolConfigurationSupplier.java` |

**唯一现成资产**：`TransactionSynchronization` 的键已经是 `IdentityHashMap<DataSource, ConnectionContext>`（`:40-41`）——per-DataSource 骨架在。缺的是上层：类型化标识、路由策略、跨库工作单元。
