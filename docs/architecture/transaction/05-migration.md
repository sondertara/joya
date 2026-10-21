# 05 落地路线

## 前置决策：先建测试地基

**全仓无 `src/test`**（实测：`joya-core/pom.xml` 仅有 junit-jupiter 依赖，无嵌入式数据库）。在零测试的代码上重写事务内核等于蒙眼手术。

阶段 0 不可跳过。

```xml
<!-- joya-core/pom.xml -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
    <scope>test</scope>
</dependency>
<!-- 真实 MySQL/PG 行为验证（阶段 4 起） -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

H2 覆盖语义与并发，Testcontainers 覆盖方言差异（隔离级别实际行为、savepoint 支持、错误码）。

---

## 阶段划分

### 阶段 0：测试地基 + 缺陷冻结

**目标**：把 01 文档里每个已复现缺陷转成**失败的测试**。

| 任务 | 产出 |
|---|---|
| 引入 H2 + Testcontainers | `joya-core/pom.xml` |
| 探针转正式测试 | `TxDefectRegressionTest`（全部 `@Disabled("待阶段 N 修复")` 标注对应缺陷编号） |
| 连接生命周期断言工具 | `ConnectionSpy`（记录 `setAutoCommit`/`commit`/`rollback`/`close`/`setTransactionIsolation` 序列） |
| 池行为矩阵 | `autoCommit=true` / `autoCommit=false` 两套 fixture —— P0-3 只在后者暴露 |

**验收**：每个 P0/P1 缺陷有一个红灯测试，测试名含缺陷编号。

---

### 阶段 1：Session + 状态机（核心不变量落地）

**目标**：I1–I6、I9 落地，新旧并存。

新增 `com.sondertara.joya.core.tx`：

```
tx/
├── Tx.java                     句柄接口
├── TxImpl.java                 实现（持 Session）
├── TxOptions.java              record
├── TxState.java                状态枚举
├── Propagation.java            3 值
├── TxAssertion.java            4 值断言
├── Isolation.java              修正版（迁自 jdbc.transaction）
├── Session.java                连接所有者 + 状态机（package-private）
├── RestorePoint.java           record
├── TxRunner.java               异常合成 + 重试
├── RetryPolicy.java
├── TxListener.java / TxEvent.java
├── TxCapabilities.java
├── platform/
│   ├── TransactionPlatform.java    SPI
│   └── JoyaLocalPlatform.java      自管本地事务
└── exception/
    ├── TxException.java
    ├── IllegalTxStateException.java
    ├── UnexpectedRollbackException.java
    ├── TxThreadViolationException.java
    ├── TxAssertionException.java
    ├── TxTimeoutException.java
    └── TxRoutingViolationException.java
```

**关键实现点**（对应已复现缺陷）：

```java
// I3：物理操作仅最外层 —— 修 P0-1
void exitCommit() throws SQLException {
    checkOwner();
    requireState(TxState.ACTIVE, TxState.MARKED_ROLLBACK);
    if (--depth > 0) return;                       // ★ 参与者层直接返回，绝不碰连接
    if (globalRollbackOnly) {
        connection.rollback();
        state = TxState.ROLLED_BACK;
        throw new UnexpectedRollbackException(...); // I7，不静默
    }
    connection.commit();
    state = TxState.COMMITTED;
}

// I5：不校验、不换连接 —— 修 P1-3
Connection connection() {
    checkOwner();
    return connection;    // final 字段，结构上不可能热替换
}

// I2：状态显式 —— 修 P0-3
boolean inTransaction() {
    return state.isActive();    // ★ 绝不 !connection.getAutoCommit()
}
```

**验收**：

- 阶段 0 中 P0-1 / P0-3 / P1-1 / P1-2 / P1-3 的红灯测试转绿
- 新增：状态机非法迁移矩阵测试（每个非法迁移抛 `IllegalTxStateException`）
- 新增：`autoCommit=false` 池下全部传播行为行为正确
- 新增：跨线程使用 `Tx` 抛 `TxThreadViolationException`

---

### 阶段 2：TxRunner + 重试 + 可观测

**目标**：I7、I8 落地 + 新能力。

| 任务 | 验收 |
|---|---|
| `TxRunner` 收敛所有执行路径 | 全仓 grep 无裸 `finally { commit(); }` |
| 异常合成 | 业务异常 + rollback 失败 → primary 是业务异常，rollback 异常在 `getSuppressed()`（修 P1-4） |
| 重试 | 40001/40P01/1213/1205 重试；RAII 形态 `retry != NONE` 抛 `IllegalArgumentException` |
| `TxListener` | begin/commit/rollback/retry 事件序列断言 |
| 泄漏检测 | 持有超阈值触发 `onLeakSuspected` 并携带借出栈 |

---

### 阶段 3：DataSourceRegistry + 路由

**目标**：多数据源单库事务可用。

| 任务 | 验收 |
|---|---|
| `DataSourceId` + `DataSourceRegistry` + `TxCapabilities` 启动期探测 | 热路径无 `DatabaseMetaData` 调用（对比现状 `ConnectionContext.java:165-167`） |
| `RoutingStrategy`，begin 时解析一次 | 写事务内的读**必定**落主库（Spring 路由数据源经典 bug 回归测试） |
| `SqlInterceptor.mayChangePhysicalDatabase()` + 链上校验 | 事务中改库的拦截器抛 `TxRoutingViolationException` |
| 移除 `@ConditionalOnSingleCandidate`（`JoyaJdbcAutoConfiguration.java:33`） | 双 `DataSource` 容器下自动配置正常生效 |
| `readOnly` 真正调 `setReadOnly(true)` | H2/PG 上只读事务内写操作失败 |

---

### 阶段 4：UnitOfWork + Spring 互操作

**目标**：跨库有序 1PC + 修复 Spring 事务分裂（P0）。

| 任务 | 验收 |
|---|---|
| `UnitOfWork` 有序提交 | 第 2 个库提交失败 → `PartialCommitException.committed() == [db1]` |
| 预检阶段 | 任一 `MARKED_ROLLBACK` → 全部回滚，**不进入提交阶段** |
| `PartialCommitHandler` | 部分提交时回调触发 |
| `SpringTxPlatform` | Spring `@Transactional` 内调 joya → **同一物理连接**；Spring 回滚 → joya 写入一并回滚 |
| starter 平台选择 | `PlatformTransactionManager` 在场 → `SpringTxPlatform`；否则 `JoyaLocalPlatform` |

**Spring 互操作测试是本阶段重点**——它修的是现状最隐蔽的 P0：

```java
@Test
@Transactional      // Spring 事务
void joyaWritesJoinSpringTransaction() {
    Connection springConn = DataSourceUtils.getConnection(dataSource);
    joya.txRun(tx -> tx.insertInto(User.class).values(u).execute());
    // 断言 1：joya 用的是 Spring 的连接，未另借
    assertThat(spy.borrowCount()).isEqualTo(1);
    // 断言 2：joya 未发出物理 COMMIT
    assertThat(spy.ops()).doesNotContain("COMMIT");
    TestTransaction.flagForRollback();
    TestTransaction.end();
    // 断言 3：Spring 回滚后 joya 的写入不可见
    assertThat(countUsers()).isZero();
}
```

---

### 阶段 5：迁移 + 清理

**目标**：单一事务实现，零遗留路径。

#### 删除清单（清晰切换，不留兼容 shim）

| 文件 | 处置 |
|---|---|
| `jdbc/transaction/LocalTransactionManager.java` | 删除 → `JoyaLocalPlatform` |
| `jdbc/transaction/TransactionManager.java` | 删除 → `TransactionPlatform` |
| `jdbc/transaction/AbstractTransactionStatus.java` | 删除 → `Session` + `Tx` |
| `jdbc/transaction/TransactionStatus.java` | 删除 → `Tx` |
| `jdbc/transaction/TransactionTemplate.java` | 删除 → `JoyaTemplate.tx(...)` |
| `jdbc/transaction/TransactionTemplateImpl.java` | 删除 → `TxRunner` |
| `jdbc/transaction/TransactionalInterceptor.java` | 删除（当前已是死代码，零引用）→ 新拦截器 |
| `jdbc/transaction/Propagation.java`（7 值） | 删除 → `tx/Propagation`（3 值）+ `tx/TxAssertion` |
| `jdbc/transaction/Isolation.java` | 迁移到 `tx/`，修正 `DEFAULT.getValue()` 与 `fromJdbcValue(0)` |
| `jdbc/transaction/SavepointManager.java` | 迁移到 `tx/`，由 `Session` 实现 |
| `jdbc/support/TransactionSynchronization.java` | **删除**（全局 `ThreadLocal` 静态类） |
| `jdbc/support/ConnectionContext.java` | **删除** → `Session` |
| `kernel/tx/TransactionScope.java` | 删除 → `Tx`（RAII 直觉被 `Tx` 继承） |
| `kernel/JoyaTransactionScope.java` | 删除 → `TxImpl` |
| `jdbc/callback/TransactionCallback.java` | 删除 → `TxCallback` |

#### 调用点迁移

| 位置 | 现状 | 迁移后 |
|---|---|---|
| `JoyaJdbc.java:116` | `new LocalTransactionManager(dataSource)` | 注入 `TransactionPlatform` |
| `JoyaJdbc.java:139-142` `inTransaction()` | `ctx.hasTransaction()` 推断 | `txContext.current().isPresent()` |
| `JoyaJdbc.java:430-432` `transactionTemplate()` | `new TransactionTemplateImpl(...)` | 删除，改 `tx(...)` |
| `JoyaJdbc.java:448-454` `doInTransactionCallback` | 同上 | 删除 |
| `JoyaJdbc.java:456-458` `obtainConnection()` | `TransactionSynchronization.getOrCreate(...)` | 删除（`Session` 拥有连接） |
| `JoyaJdbc.java:471-487` `doInTransaction` | 事务内显式 `release`（P1-2 根源） | 删除 → `tx(...)` |
| `JoyaTemplate.java:59` | `new LocalTransactionManager(dataSource)` | 注入 `TransactionPlatform` |
| `JoyaTemplate.java:161-163` `begin(Isolation)` | `new JoyaTransactionScope(...)` | `begin(TxOptions)` |
| `JdbcExecutor.java:76-78` `obtainConnection()` | `getOrCreate(...).getConnection()` | 接收 `Session`，不自行获取 |
| `JdbcExecutor.java:89,127,133,216,222,321` `release(...)` | 6 处引用计数释放 | 全部删除（I6） |
| `JoyaClient.java:147-153` `begin(...)` | 透传 `TransactionScope` | 透传 `Tx` |
| `JdkMapperProxy` | 无事务织入 | 织入 `TransactionalInterceptor` 新版 |

#### 文档同步（现状已漂移）

`AGENTS.md` 列出的以下类**不存在**（实测 find 结果为 0），必须一并修正：

```
SyncManager · TransactionObject · TransactionTemplateManager
LocalTransactionStatus · SimpleJoyaMapper
```

同时更新：
- `AGENTS.md` 的 `jdbc/tran/` 章节 → 实际路径 `jdbc/transaction/`，重构后为 `core/tx/`
- 「关键架构决策」1–3 条（统一连接体系 / 引用计数 / 事务栈）已全部作废，替换为九条不变量
- `docs/_sidebar.md` 增加架构文档入口

---

## 测试矩阵（阶段 5 完成时必须全绿）

### 传播行为 × 池 autoCommit 配置

| 场景 | `autoCommit=true` 池 | `autoCommit=false` 池 |
|---|---|---|
| `REQUIRED` 单层 commit/rollback | ✓ | ✓ ← 现状此列全崩（P0-3） |
| `REQUIRED` 嵌套：内层 commit，外层 rollback | ✓ 全部回滚 | ✓ |
| `REQUIRED` 嵌套：内层 setRollbackOnly，外层 commit | ✓ `UnexpectedRollbackException` | ✓ |
| `REQUIRES_NEW`：内层 commit，外层 rollback | ✓ 内层独立生效 | ✓ |
| `REQUIRES_NEW`：内层异常，外层继续 | ✓ | ✓ |
| `NESTED`：内层 rollback，外层 commit | ✓ 外层生效 ← 现状 P0-1 | ✓ |
| `NESTED` 无外层 | ✓ `IllegalTxStateException` | ✓ |
| `MANDATORY` 无事务 | ✓ `TxAssertionException` | ✓ ← 现状误判通过 |
| `NEVER` 无事务 | ✓ 通过 | ✓ ← 现状误判抛错 |
| `NOT_SUPPORTED` 有事务 | ✓ 挂起 + 独立非事务连接 | ✓ |

### 连接生命周期

| 断言 | 验证手段 |
|---|---|
| 事务内 SQL 复用同一 `Connection` | `ConnectionSpy.borrowCount() == 1` |
| 事务内**零** `isValid()` 调用 | `spy.isValidCount() == 0`（现状 100 次调用 99 次 ping） |
| 归还时 `autoCommit` / 隔离 / `readOnly` 全部复原 | 嵌套改隔离后归还，spy 断言恢复到**原始**值（现状 P1-1 恢复成外层值） |
| 每个 `Session` 的连接恰好 `close()` 一次 | `spy.closeCount() == 1` |
| 异常路径不泄漏 | begin → 业务异常 → 断言 `closeCount() == 1` |
| 流式查询在事务内 | `Stream` 关闭不影响事务，事务结束才归还连接 |

### 并发（修 P0-2）

| 断言 | 手段 |
|---|---|
| 单例平台下 N 线程各自独立事务 | 100 线程 × 各自 commit/rollback，断言互不影响 |
| 跨线程使用 `Tx` 抛错 | 线程 A 创建，线程 B 调 `commit()` → `TxThreadViolationException` |
| 虚拟线程下正确 | 同上，`Executors.newVirtualThreadPerTaskExecutor()` |
| `StructuredTaskScope` fork 子任务误用 | 子任务用父 `Tx` → `TxThreadViolationException`，不静默错乱 |

### 异常合成（修 P1-4）

| 场景 | 断言 |
|---|---|
| 业务异常 + rollback 成功 | 抛业务异常，`suppressed` 为空 |
| 业务异常 + rollback 失败 | 抛**业务**异常，rollback 异常在 `suppressed`（现状被替换） |
| 业务成功 + commit 失败 | 抛 commit 异常 |
| 业务异常 + close 失败 | 抛业务异常，close 异常在 `suppressed` |

### 多数据源

| 场景 | 断言 |
|---|---|
| 写事务内读不落从库 | `RoutingStrategy` 钉死后读走主库 |
| `UnitOfWork` 全部成功 | 各库均提交，顺序 == `registry.ids()` |
| `UnitOfWork` 第 2 库失败 | `PartialCommitException.committed() == [db1]`，db2 回滚 |
| `UnitOfWork` 预检失败 | 任一 `MARKED_ROLLBACK` → 全回滚，**零** COMMIT 发出 |
| 事务中拦截器改库 | `TxRoutingViolationException` |

### Spring 互操作（修隐蔽 P0）

| 场景 | 断言 |
|---|---|
| Spring `@Transactional` 内调 joya | 同一物理连接，`borrowCount() == 1` |
| Spring 回滚 | joya 写入一并回滚 |
| joya 内部异常 | Spring `TransactionStatus.isRollbackOnly() == true` |
| 无 Spring 事务 | joya 自管，正常提交 |

---

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| API 破坏性变更 | 全新框架，无外部用户负担；`JoyaTemplate` 自动提交路径签名不变，破坏面限于事务 API |
| `Tx` 需显式传递，比环境态"啰嗦" | 函数式形态 + `tx.mapper(Class)` 派生；`@Transactional` 兼容层保留便利性 |
| `NESTED` 无外层从退化改为报错 | 现状该路径本就产生错误语义（P0-3 实测在无事务连接上建 savepoint），无正确行为可保 |
| 默认回滚规则改为"任何异常" | 阶段 5 变更日志明确标注；`noRollbackFor` 作为逃生舱 |
| 阶段跨度大 | 每阶段独立可交付、测试全绿；新旧包并存至阶段 5 |
| 重试改变副作用语义 | 默认 `RetryPolicy.NONE`；仅函数式可用；文档明确"回调必须幂等" |

---

## 附：探针代码归档

阶段 0 应把 `/tmp/txprobe/TxProbe{,2,3,4}.java` 迁入 `joya-core/src/test/java/.../tx/TxDefectRegressionTest.java`。

探针技术要点（值得保留为长期测试基础设施）：

- **JDK 动态代理伪造 `Connection`/`DataSource`**：无需真实数据库即可断言 `setAutoCommit`/`commit`/`rollback`/`close`/`setTransactionIsolation` 的**精确调用序列**。事务正确性的本质就是这个序列正确。
- **`ops` 列表断言**：比"最终数据对不对"更早、更精准地定位缺陷。P0-1 的 `[setAutoCommit(false), SAVEPOINT, ROLLBACK_TO_SP, setAutoCommit(true)]` 一眼看出外层事务被隐式提交。
- **池 `autoCommit` 参数化**：P0-3 只在 `autoCommit=false` 时暴露。**任何事务测试都必须跑两遍**（两种池配置），这是 fixture 的强制维度。
- **`ConnectionSpy` 应支持**：`borrowCount` / `closeCount` / `isValidCount` / `ops()` / 借出栈快照。
