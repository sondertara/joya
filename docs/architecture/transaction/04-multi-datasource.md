# 04 多数据源

> **历史方案说明（2026-10）**：文中依赖的 `ShardingInterceptor` 已移除，执行期改表不再受支持；动态表名改由 `NamingPolicy.tableNameResolver` 在渲染期完成。


## 现状

不支持，且 starter 主动拒绝：`@ConditionalOnSingleCandidate(DataSource.class)`（`JoyaJdbcAutoConfiguration.java:33`）—— 容器里有多个 `DataSource` 时**整个自动配置静默失效**。

唯一现成资产：`TransactionSynchronization` 的键已是 `IdentityHashMap<DataSource, ConnectionContext>`（`:40-41`），per-DataSource 骨架在。缺上层：类型化标识、路由策略、跨库工作单元。

---

## 分层目标

```
DataSourceId（类型化标识，非 String 魔法）
     │
     ▼
DataSourceRegistry
     │  DataSourceId → Handle{DataSource, Dialect, TxCapabilities, Role}
     ▼
RoutingStrategy（事务开始时解析一次并钉死）
     │
     ├── 单库事务    → Tx（一个 Session）
     └── 跨库事务    → UnitOfWork（多个 Session，有序 1PC）
                        └── 可选插件 → JtaXaPlatform（Agroal TransactionIntegration）
```

---

## DataSourceId 与注册表

```java
/** 类型化数据源标识。禁止裸 String —— 拼写错误应在编译期而非运行期暴露。 */
public record DataSourceId(String value) implements Comparable<DataSourceId> {

    public static final DataSourceId DEFAULT = new DataSourceId("default");

    public DataSourceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dataSource id must not be blank");
        }
    }

    /** 有序提交需要确定序（见下文"有序 1PC"）。 */
    @Override public int compareTo(DataSourceId o) { return value.compareTo(o.value); }
}
```

推荐用法：在应用中声明常量，获得 IDE 补全与重构支持。

```java
public interface Dbs {
    DataSourceId ORDERS  = new DataSourceId("orders");
    DataSourceId BILLING = new DataSourceId("billing");
}
```

```java
public interface DataSourceRegistry {

    Handle resolve(DataSourceId id);

    /** 注册顺序即提交顺序（见"有序 1PC"）。 */
    List<DataSourceId> ids();

    /**
     * @param role   主/从角色，供读写分离路由
     * @param caps   事务能力（savepoint / 隔离级别 / XA 支持），启动期探测一次并缓存
     */
    record Handle(DataSourceId id, DataSource dataSource, Dialect dialect,
                  TxCapabilities caps, Role role, DataSourceId primaryOf) {}

    enum Role { PRIMARY, REPLICA }
}
```

```java
/**
 * 事务能力。启动期探测一次（DatabaseMetaData），运行期只读缓存。
 *
 * <p>解决现状问题：ConnectionContext.supportSavepoint() 每次调用都取
 * DatabaseMetaData（ConnectionContext.java:165-167），事务热路径上的元数据往返。
 */
public record TxCapabilities(
        boolean savepoints,
        boolean readOnlyTransactions,
        Set<Isolation> supportedIsolations,
        boolean xa
) {}
```

---

## 路由：事务开始时解析一次并**钉死**

```java
public interface RoutingStrategy {

    /**
     * 在事务开始时调用**恰好一次**。返回值在整个事务生命周期内不变。
     *
     * @param options 事务选项（readOnly 是主要路由输入）
     * @param hint    可选业务提示（分片键、租户 ID）
     */
    DataSourceId route(TxOptions options, RoutingHint hint);

    /** 单库直连。 */
    static RoutingStrategy single(DataSourceId id) { ... }

    /** 读写分离：写事务 → 主库；只读事务 → 从库轮询。 */
    static RoutingStrategy readWriteSplit(DataSourceId primary, List<DataSourceId> replicas) { ... }
}
```

### 这条设计直接规避 Spring `AbstractRoutingDataSource` 两个经典 bug

| Spring 的坑 | 本设计如何规避 |
|---|---|
| `@Transactional` 在路由键设置前就取了连接 → 路由到错误库 | 路由**是** `begin` 流程的第一步，`Session` 创建之前完成，不存在时序竞争 |
| 写事务内的读被路由到从库 → 读不到自己刚写的数据 | 路由结果钉在 `Session` 上，事务内所有语句复用同一 `Session`，**物理上不可能**跑到别的库 |

```java
// begin 流程中路由与连接获取的顺序
DataSourceId resolved = routing.route(options, hint);      // 1. 先路由
Handle handle = registry.resolve(resolved);                // 2. 再解析
Session session = Session.owner(resolved,
        handle.dataSource().getConnection(), options);      // 3. 最后借连接
// session.dataSourceId 此后不可变 —— 事务内所有 SQL 走它
```

### 只读事务与从库

`TxOptions.readOnly = true` 是路由的主要输入，且新设计**真正调用** `Connection.setReadOnly(true)`（现状仅走回滚路径，从不设置）。这带来三重收益：

1. 从库路由天然安全——连接层也标记只读，误写立即失败
2. PostgreSQL 可用 `SET TRANSACTION READ ONLY` 优化
3. MySQL 只读事务跳过部分锁开销

### 分片拦截器的硬约束

`SqlInterceptor` 链（`kernel/interceptor/`，示例 `ShardingInterceptor`）**不得在事务中把语句改写到另一个物理库**。

```java
public interface SqlInterceptor extends Ordered {
    /**
     * 声明本拦截器是否可能改变语句的目标**物理库**。
     *
     * <p>返回 true 的拦截器在事务上下文中执行时，链上校验器会抛
     * {@link TxRoutingViolationException} —— 分片 + 事务若无此约束必然静默错乱。
     */
    default boolean mayChangePhysicalDatabase() { return false; }
}
```

现状 `ShardingInterceptor` 在 SQL 改写层，与连接选择、事务绑定**完全脱钩**——语句被改写到 `db2.orders` 却仍在 `db1` 的连接上执行。这个约束必须在链上强制校验，不能只写文档。

---

## 跨库：UnitOfWork（有序 1PC）

### 设计立场：不假装原子

```java
/**
 * 跨数据源工作单元 —— 有序 1PC，<b>非原子</b>。
 *
 * <p>提交按 {@link DataSourceRegistry#ids()} 的注册序执行。第 N 个失败时，
 * 第 1..N-1 个<b>已经提交且无法回滚</b>，抛 {@link PartialCommitException}
 * 并携带已提交列表。
 *
 * <p>这是刻意的诚实：真正的原子性需要 2PC（{@code JtaXaPlatform} 插件）。
 * 隐藏这个代价比暴露它危险得多。
 */
public interface UnitOfWork {

    /** 获取（或懒创建）指定数据源上的事务句柄。 */
    Tx on(DataSourceId dataSource);

    void setRollbackOnly();

    Set<DataSourceId> enlisted();
}
```

### 提交协议

```
阶段 1  预检
   所有 enlisted Session 状态必须为 ACTIVE
   任一为 MARKED_ROLLBACK → 全部回滚 → UnexpectedRollbackException
                                       （不进入提交阶段）

阶段 2  有序提交（按 registry.ids() 序，确定性 —— 便于事故复盘）
   for (dsId : enlisted 按注册序) {
       try   { session.commit(); committed.add(dsId); }
       catch { remaining 全部 rollback;
               throw PartialCommitException(committed, dsId, cause,
                                            listener 已收到 onPartialCommit); }
   }

阶段 3  释放
   所有 Session 恢复 RestorePoint 并归还连接（每个恰好一次，I6）
```

### 排序为什么重要

固定注册序而非 `HashSet` 迭代序，使**故障模式可复盘**：事故时能确定"orders 已提交、billing 未提交"，而不是"某两个提交了，取决于 hash 分布"。

推论：**注册序应把最可能失败、或补偿代价最小的数据源放在最后。**

### 补偿钩子（outbox）

```java
public interface PartialCommitHandler {
    /**
     * 部分提交发生时调用。典型实现：向 outbox 表写补偿任务。
     *
     * <p>注意：本回调运行在**已提交**的数据源之外——若需持久化补偿意图，
     * 应写入独立的补偿库，或在业务事务内预先写 outbox 记录（推荐）。
     */
    void onPartialCommit(PartialCommitException e);
}
```

**推荐模式**：业务事务内预写 outbox 记录 + 后台补偿器（事务性发件箱）。这是跨库最终一致性的主流做法，比在异常回调里补偿可靠得多——回调本身可能因进程崩溃而不执行。

---

## XA 插件（可选，不进核心）

树里已有完整但休眠的 XA 管线（`joya-datasource/src/main/java/io/agroal/`）：

| 类 | 状态 |
|---|---|
| `api/transaction/TransactionIntegration` | 接口完整，默认 `none()` |
| `api/transaction/TransactionAware` | 接口完整 |
| `pool/ConnectionHandler` | `implements TransactionAware`，`transactionStart/Commit/Rollback/End` 全实现 |
| `pool/wrapper/XAConnectionWrapper` | 真实 `XAConnection` 包装器，功能完整 |
| `pool/wrapper/XAResourceWrapper` | `commit/prepare/start/end/recover` 全实现 |
| `AgroalConnectionPoolConfigurationSupplier` | 默认 `transactionIntegration = none()`、`transactionRequirement = OFF` |

`JoyaDataSource` 走 DRIVER 模式（`connectionProviderClassName`），故整条链休眠，且 joya 自身从未实现 `TransactionIntegration`。

### 启用路径

```java
// 新模块：joya-tx-jta
final class JoyaTransactionIntegration implements TransactionIntegration {
    // 桥接 JTA TransactionManager 与 Agroal ConnectionHandler.transactionStart/End
}

final class JtaXaPlatform implements TransactionPlatform {
    // Session.enlist(XAResource) 委托 JTA TransactionManager
}
```

配置：`JoyaDataSource` 改用 `AgroalConnectionFactoryConfiguration.Mode.XA_DATASOURCE` + 注入 `JoyaTransactionIntegration`。

### 为什么不进核心

| 理由 | 说明 |
|---|---|
| XA 需外部 TM | Narayana / Atomikos，是重量级依赖 |
| 性能代价高 | 2PC 至少两轮网络往返 + 事务日志持久化 |
| 恢复复杂 | 需要 recovery 管理器 + 手工干预流程 |
| 使用率低 | 绝大多数业务用有序 1PC + outbox 已足够 |

**明确不做 Seata AT 模式**：全局锁 + `undo_log` 侵入式设计要求框架代理所有 SQL 并解析生成反向语句，与 Joya"SQL 透明、零魔法"的定位冲突，且给未来背上重债。

---

## 配置形态

```yaml
joya:
  datasources:
    orders:
      url: jdbc:mysql://db1:3306/orders
      username: app
      password: ${DB_PASSWORD}
      role: primary
      pool:
        max-size: 20
        # 显式声明。事务内不再校验（I5），此项由池负责
        validate-on-borrow: true
    orders-replica:
      url: jdbc:mysql://db1-ro:3306/orders
      role: replica
      primary-of: orders
    billing:
      url: jdbc:postgresql://db2:5432/billing
      role: primary

  transaction:
    default-datasource: orders
    # 注册序 = 提交序。最可能失败者放最后
    commit-order: [orders, billing]
    routing: read-write-split
    # 无环境事务时 @Transactional 的行为
    ambient-policy: require-tx
    leak-detection-threshold: 30s
    listeners:
      - com.example.OtelTxListener
```

---

## 与现有代码的衔接

| 现有 | 变更 |
|---|---|
| `DataSourceFactory.getName()` 恒返回 `"default"`（`:14-16`） | 返回 `DataSourceId`；由配置驱动 |
| `DefaultDataSourceFactory` | 保留，注册进 `DataSourceRegistry` |
| `JoyaDataSource` | 不变（单 DS 薄封装职责正确）；XA 模式作为插件开关 |
| `JoyaJdbcAutoConfiguration:33` `@ConditionalOnSingleCandidate` | **移除**，改为注册全部 `DataSource` bean + 配置声明的数据源 |
| `TransactionSynchronization` 的 `IdentityHashMap<DataSource, …>` | 思路继承到 `DataSourceRegistry`，类本身删除 |
| `kernel/interceptor/example/ShardingInterceptor` | 增加 `mayChangePhysicalDatabase()` 声明并纳入链上校验 |
