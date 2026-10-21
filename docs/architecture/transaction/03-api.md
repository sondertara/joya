# 03 API 契约

包路径：`com.sondertara.joya.core.tx`（新建，与旧 `core.jdbc.transaction` 并存至阶段 5 删除）

---

## 使用形态

### 形态 1：函数式（首选）

句柄无法逃逸，可重试，无需 `finally`。

```java
long orderId = joya.tx(tx -> {
    tx.insertInto(User.class).values(user).execute();
    return tx.mapper(Order.class).save(order);      // 派生 tx 绑定的 mapper
});
```

无返回值：

```java
joya.txRun(tx -> {
    tx.update(Account.class).set(Account::getBalance, newBalance).where(...).execute();
});
```

### 形态 2：带策略

```java
joya.tx(TxOptions.builder()
        .isolation(Isolation.SERIALIZABLE)
        .readOnly(false)
        .propagation(Propagation.REQUIRES_NEW)
        .retry(RetryPolicy.onSerializationFailure(3))    // 仅函数式可用
        .timeout(Duration.ofSeconds(5))
        .build(),
    tx -> { ... });
```

### 形态 3：RAII（跨方法 / 流式查询）

```java
try (Tx tx = joya.begin(TxOptions.DEFAULT)) {
    tx.update(...).execute();
    try (Stream<Row> s = tx.queryStream(stmt, mapper)) {
        s.forEach(this::handle);            // 流在事务内消费，连接归 tx 所有
    }
    tx.commit();                            // 未 commit → close() 回滚
}
```

`close()` 未提交即回滚。`retry` 非 `NONE` 时 `begin` 抛 `IllegalArgumentException`——body 不可重放。

### 形态 4：只读

```java
List<Report> rows = joya.txReadOnly(tx -> tx.queryList(stmt, mapper));
```

真正设置 `Connection.setReadOnly(true)`（对比现状：仅走回滚路径，从不设置）。多数据源下自动倾向从库（见 [04](04-multi-datasource.md)）。

### 形态 5：多数据源工作单元

```java
joya.unitOfWork(uow -> {
    uow.on(ORDERS_DB).insertInto(Order.class).values(o).execute();
    uow.on(BILLING_DB).update(Invoice.class).set(...).execute();
});
// 按注册序有序提交；中途失败 → PartialCommitException(已提交列表)
```

**不假装原子。** 详见 [04 多数据源](04-multi-datasource.md)。

---

## 核心接口

### Tx

```java
/**
 * 事务句柄 —— 事务内 SQL 的唯一执行入口（不变量 I1）。
 *
 * <p>线程约束：只能在创建它的线程使用（不变量 I9）。跨线程调用抛
 * {@link TxThreadViolationException}，为 Loom 结构化并发下的误用提供即时反馈。
 *
 * <p>函数式形态（{@code joya.tx(...)}）下句柄在回调返回后失效，
 * 逃逸使用抛 {@link IllegalTxStateException}。
 */
public interface Tx extends AutoCloseable {

    // ===== 生命周期 =====

    /**
     * 提交。仅最外层（{@code depth == 0}）执行物理提交（不变量 I3）。
     *
     * @throws UnexpectedRollbackException 事务已被标记回滚（不变量 I7）—— 不静默
     */
    void commit();

    /** 回滚。仅最外层执行物理回滚；参与者层标记全局 rollback-only。 */
    void rollback();

    /** 标记回滚。最外层 {@code commit()} 将抛 {@link UnexpectedRollbackException}。 */
    void setRollbackOnly();

    boolean isRollbackOnly();

    TxState state();

    /** 未提交则回滚。幂等。 */
    @Override
    void close();

    // ===== savepoint =====

    /** @throws IllegalTxStateException 当前不在事务中（不退化为新事务） */
    Savepoint savepoint();

    void rollbackTo(Savepoint savepoint);

    void release(Savepoint savepoint);

    // ===== SQL 执行（与 JoyaTemplate 同签名，语义为"在本事务内"） =====

    <T> T query(SqlStatement statement, RowMapper<T> mapper);

    <T> List<T> queryList(SqlStatement statement, RowMapper<T> mapper);

    /** 流在本事务内消费；连接由 Tx 拥有，Stream 关闭不影响事务。 */
    <T> Stream<T> queryStream(SqlStatement statement, RowMapper<T> mapper);

    int update(SqlStatement statement);

    Object insert(SqlStatement statement);

    int[] batchUpdate(SqlStatement statement, List<Object[]> batchArgs);

    <T> PageResult<T> queryPage(SqlStatement statement, RowMapper<T> mapper, PageParam page);

    // ===== 派生：Entity DSL / Mapper 绑定到本事务 =====

    <T> InsertBuilder<T> insertInto(Class<T> entityType);

    <T> UpdateBuilder<T> update(Class<T> entityType);

    <T> DeleteBuilder<T> deleteFrom(Class<T> entityType);

    /** 返回绑定到本事务的 mapper —— 不再依赖环境态定位事务。 */
    <T> JoyaMapper<T> mapper(Class<T> entityType);

    // ===== 多数据源 =====

    /**
     * 切换到指定数据源。仅在 {@link UnitOfWork} 中可用；
     * 单数据源 Tx 上调用非本 Tx 的 dsId 抛 {@link IllegalArgumentException}。
     */
    Tx on(DataSourceId dataSource);

    DataSourceId dataSource();
}
```

### TxOptions

```java
public record TxOptions(
        Propagation propagation,
        Isolation isolation,
        boolean readOnly,
        RetryPolicy retry,
        Duration timeout,
        DataSourceId dataSource      // null = 由路由策略决定
) {
    public static final TxOptions DEFAULT =
            new TxOptions(Propagation.REQUIRED, Isolation.DEFAULT, false,
                          RetryPolicy.NONE, Duration.ZERO, null);

    public static final TxOptions READ_ONLY = DEFAULT.toBuilder().readOnly(true).build();

    public static Builder builder() { ... }

    public TxOptions {
        // 契约校验前置到构造期，而非运行到一半才发现
        if (propagation == Propagation.NESTED && retry != RetryPolicy.NONE) {
            throw new IllegalArgumentException("NESTED 事务不支持重试：savepoint 回滚后外层状态不可重放");
        }
    }
}
```

### Propagation / TxAssertion

```java
/** 运行时传播行为 —— 仅 3 值（详见 02-design 传播语义收敛）。 */
public enum Propagation {
    /** 有则加入（仅登记，不碰连接），无则新建。 */
    REQUIRED,
    /** 总是独立事务：独立 Session + 独立连接，外层原样挂起。 */
    REQUIRES_NEW,
    /** savepoint 子事务。要求已在事务中，否则抛错（不退化）。 */
    NESTED
}

/** 事务前置断言 —— 不是传播行为，可在启动期静态校验。 */
public enum TxAssertion {
    NONE,
    MANDATORY,       // 必须已在事务中
    NEVER,           // 必须不在事务中
    SUPPORTS,        // 无约束（表达意图用）
    NOT_SUPPORTED    // 必须不在事务中；若在则挂起外层，独立连接非事务执行
}
```

### TxState

```java
public enum TxState {
    NONE,
    ACTIVE,
    MARKED_ROLLBACK,
    COMMITTED,
    ROLLED_BACK;

    public boolean isTerminal() { return this == COMMITTED || this == ROLLED_BACK; }
    public boolean isActive()   { return this == ACTIVE || this == MARKED_ROLLBACK; }
}
```

### Session（内部，不对用户暴露）

```java
/**
 * 连接所有者 + 事务状态机。
 *
 * <p>不变量：
 * <ul>
 *   <li>I6 独占一个 {@link Connection}，恰好 {@code close()} 一次。无引用计数。</li>
 *   <li>I5 {@code ACTIVE} 期间连接永不更换、永不校验。</li>
 *   <li>I3 物理 commit/rollback/setAutoCommit 仅在 {@code depth == 0} 执行。</li>
 *   <li>I9 仅 {@code ownerThread} 可访问。</li>
 * </ul>
 */
final class Session implements AutoCloseable, SavepointManager {

    private final DataSourceId dataSourceId;
    private final Connection connection;        // final —— 结构上不可能热替换
    private final Thread ownerThread;           // I9
    private final RestorePoint restorePoint;    // I4，构造时捕获一次

    private TxState state = TxState.NONE;       // I2，绝不从 autoCommit 反推
    private int depth;                          // I3
    private boolean globalRollbackOnly;         // I7
    private int savepointCounter;
    private long acquiredAtNanos;               // 泄漏检测

    /** 每个公开方法首行调用。 */
    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new TxThreadViolationException(ownerThread, Thread.currentThread());
        }
    }

    /** @return true 表示本层是最外层（isNew） */
    boolean enter(TxOptions options) throws SQLException { ... }

    void exitCommit() throws SQLException { ... }

    void exitRollback() throws SQLException { ... }

    void markGlobalRollbackOnly() { ... }

    /** I5：不校验、不换连接。 */
    Connection connection() { checkOwner(); return connection; }

    /** I4 + I6：恢复状态后归还连接，幂等。 */
    @Override public void close() { ... }
}
```

### TxRunner（异常合成 + 重试，I8）

```java
/**
 * 收敛所有事务执行路径。<b>禁止在别处写裸 {@code finally { commit(); }}</b>
 * —— 那会用清理异常替换业务异常（现状 P1-4）。
 */
final class TxRunner {

    <R> R run(TxOptions options, TxCallback<R> body) {
        int attempt = 0;
        while (true) {
            attempt++;
            Tx tx = platform.begin(options);
            Throwable primary = null;
            try {
                R result = body.apply(tx);
                tx.commit();                    // 在 try 内，异常正常传播
                return result;
            } catch (Throwable t) {
                primary = t;
                throw rollbackAndCompose(tx, t, options, attempt);
            } finally {
                // close 幂等；异常挂到 primary 的 suppressed 上（I8）
                closeQuietly(tx, primary);
            }
        }
    }

    /**
     * 异常合成规则（I8）：
     * 1. 业务异常始终是 primary
     * 2. 回滚 / 关闭失败一律 addSuppressed，绝不替换 primary
     * 3. 可重试的 SQLException 且未超次数 → 不抛，continue 重试
     */
    private RuntimeException rollbackAndCompose(Tx tx, Throwable primary,
                                                TxOptions options, int attempt) { ... }
}
```

### 异常体系

```java
DbException                              // 现有根异常，保持
├── TxException
│   ├── IllegalTxStateException          // 非法状态迁移 / 句柄逃逸 / NESTED 无外层
│   ├── UnexpectedRollbackException      // I7：标记回滚却调 commit
│   ├── TxThreadViolationException       // I9：跨线程使用 Session/Tx
│   ├── TxAssertionException             // MANDATORY / NEVER 断言失败
│   ├── TxTimeoutException
│   └── PartialCommitException           // 多 DS 有序提交中途失败，携带已提交列表
```

`PartialCommitException` 必须携带**已提交的数据源列表**——运维需要它来决定补偿范围。这是有序 1PC 相对 XA 的诚实代价，必须暴露而非隐藏。

### JoyaTemplate 新增（自动提交路径保持不变）

```java
public final class JoyaTemplate {

    // ===== 事务：函数式（首选） =====
    public <R> R tx(TxCallback<R> body) { ... }
    public <R> R tx(TxOptions options, TxCallback<R> body) { ... }
    public void txRun(Consumer<Tx> body) { ... }
    public <R> R txReadOnly(TxCallback<R> body) { ... }

    // ===== 事务：RAII =====
    /** @throws IllegalArgumentException options.retry() != NONE（body 不可重放） */
    public Tx begin(TxOptions options) { ... }
    public Tx begin() { return begin(TxOptions.DEFAULT); }

    // ===== 多数据源 =====
    public <R> R unitOfWork(Function<UnitOfWork, R> body) { ... }

    // ===== 自动提交路径：签名不变，语义明确为"非事务" =====
    public <T> List<T> queryList(SqlStatement s, RowMapper<T> m) { ... }
    public int update(SqlStatement s) { ... }
    // ...
}
```

**关键区别**：`joya.update(...)` 与 `tx.update(...)` 是不同方法。前者自动提交，后者事务内。**不存在"我以为在事务里"**——这是消灭环境态语义模糊的核心手段。

---

## 声明式 `@Transactional` 兼容层

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

    Propagation propagation() default Propagation.REQUIRED;

    /** 前置断言，独立于传播行为。 */
    TxAssertion assertion() default TxAssertion.NONE;

    Isolation isolation() default Isolation.DEFAULT;

    boolean readOnly() default false;

    int timeoutSeconds() default 0;

    /** 默认任何异常逃出即回滚（与现状"仅 unchecked 回滚"相反）。 */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /** 重试策略 SPI 实现类；默认不重试。 */
    Class<? extends RetryPolicy> retry() default RetryPolicy.None.class;
}
```

### 三处刻意的语义收紧

| 项 | 现状 | 新版 | 理由 |
|---|---|---|---|
| 默认回滚规则 | 仅 `RuntimeException`/`Error`（`TransactionalInterceptor.java:86-87`） | **任何异常逃出即回滚** | checked 异常导致静默提交是 Spring 最常见的事故来源 |
| `rollbackFor` 字符串数组 | 有（`rollbackForClassName`） | **删除** | 字符串类名匹配无编译期检查，是 bug 温床 |
| 环境事务缺失 | 静默非事务执行 | `AmbientPolicy.REQUIRE_TX` 抛错 | 静默非事务写入是灾难 |

### 环境句柄传递

```java
public enum AmbientPolicy {
    /** 无环境事务 → 自动提交执行（宽松，用于逐步迁移） */
    ALLOW_AUTOCOMMIT,
    /** 无环境事务 → 抛 TxAssertionException（推荐给 service 层，默认） */
    REQUIRE_TX
}
```

拦截器职责：解析环境句柄并**传递**给 mapper，不再让 mapper 自己从 `ThreadLocal` 捞连接。

```java
Tx ambient = txContext.current().orElse(null);
if (ambient == null && policy == REQUIRE_TX) {
    throw new TxAssertionException(method + " 要求事务上下文，但当前无活跃事务");
}
```

对比现状：`TransactionalInterceptor` 全仓零引用（死代码），`@Transactional` 完全未生效。

---

## Spring 互操作（P0 修复）

`SpringTxPlatform` 让 joya **参与** Spring 事务而非另开一个：

```java
final class SpringTxPlatform implements TransactionPlatform {

    @Override
    public Session openSession(DataSourceId id, TxOptions options) {
        DataSource ds = registry.resolve(id).dataSource();

        // Spring 已有事务 → 借它的连接，joya 绝不 commit/rollback
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Connection conn = DataSourceUtils.getConnection(ds);
            return Session.participant(id, conn, /* ownsTransaction = */ false);
        }
        // 无 Spring 事务 → joya 自管
        return Session.owner(id, ds.getConnection(), options);
    }
}
```

`ownsTransaction = false` 的 `Session`：

- `exitCommit()` **不发 COMMIT**，仅 `depth--`
- `rollback()` 调 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`，把回滚意图传给 Spring
- `close()` 走 `DataSourceUtils.releaseConnection(conn, ds)`，不物理关闭

**这修复的是**：现状两套线程局部连接体系互不可见（`TransactionSynchronization.java:40-41` vs Spring 的 `TransactionSynchronizationManager`），导致同一逻辑工作单元跑在两个物理事务里，Spring 回滚不影响 joya 已提交的部分。

### starter 装配变更

```java
@Bean
@ConditionalOnMissingBean
public TransactionPlatform joyaTransactionPlatform(
        DataSourceRegistry registry,
        ObjectProvider<PlatformTransactionManager> springTm) {
    // Spring 事务管理器在场 → 参与其事务；否则自管
    return springTm.getIfAvailable() != null
            ? new SpringTxPlatform(registry)
            : new JoyaLocalPlatform(registry);
}
```

同时移除 `@ConditionalOnSingleCandidate(DataSource.class)`（`JoyaJdbcAutoConfiguration.java:33`）——它使多数据源场景下整个自动配置静默失效。
