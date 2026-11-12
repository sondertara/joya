# 扩展点模型（AST-lite）

> 本文描述 Joya 1.x 的 SQL 扩展点分层。目标：**每个关注点有且只有一个推荐入口**，单条与批量语义一致。

## 1. 管线

```
Build     Query / Plan / Factory  →  SqlNode（内部 AST）
Rewrite   SqlRewriter（有序）      →  SqlNode（结构改写：租户、软删、审计列）
Render    SqlRenderer              →  SqlStatement（SQL + 参数 + metadata）
Execute   ExecutionKernel → Executor
Observe   SqlExecutionListener     →  日志 / 指标 / 慢查询
```

- **AST 只覆盖 Joya 生成的 SQL**；`joya.sql("...")` 原生 SQL 保持字符串，不解析，也不经过 `SqlRewriter`。
- **逃生舱边界**：`DataOps.withConnection` 与 `DataOps.script` 属于底层连接/初始化逃生舱，显式不经过 `SqlRewriter` 与 `SqlExecutionListener`（详见对应 Javadoc）。
- 唯一的 SQL 改写入口是 `SqlRewriter`。文本级拦截器已删除；hint/注释请在 SQL 生成前用 `SqlNode.Raw` 或在数据库层处理。
- `SqlExecutionListener` 只观察，不修改 SQL。

## 2. 扩展点矩阵

| 关注点 | 推荐入口 | 单条 | 批量 | 说明 |
|---|---|---|---|---|
| 分表 / schema 切换 | `NamingPolicy.tableNameResolver` | ✅ | ✅ | 配置期唯一表名机制；`SqlRewriter` 不得改表名 |
| 租户过滤 | `SqlRewriter` | ✅ | ✅（INSERT 常量列） | 改 WHERE / 追加列；**仅当本实体/本表命中 `SELECT` / `INSERT` / `UPDATE` 任一改写器**时 `save` / `saveAll` 才回退受 `WHERE` 保护路径（拒绝内层 `Insert` 静默并入无条件冲突分支），三者均未命中时保留原生 UPSERT；回退模式需整批原子性时请用 `joya.tx(...)` |
| 软删 | `EntityDeleter` / `SqlRewriter` | ✅ | ⏳ | DELETE → UPDATE 应在语句构建期建模，不在 rewriter 偷换节点类型 |
| 审计字段 | `EntityListener`（经 `EntityLifecycle`） | ✅ | ✅ | 实体字段赋值，pre 阶段；仅实体入口触发，`deleteById*` 与 `EntityUpdate`/`EntityDelete` 条件构建器不触发（见 `EntityListener` 触发矩阵） |
| SQL 日志 / 耗时 / 指标 | `SqlExecutionListener` | ✅ | ✅ | before / after / onError / onStreamClosed |
| 错误分类 / 重试 | `SqlExceptionTranslator` | ✅ | ✅ | SQLState → 语义异常 |
| 动态筛选协议 | `QueryCustomizer` | ✅ | — | 查询构建期 |
| 结果映射 | `RowMapperFactory` | ✅ | ✅ | 可由 `JoyaClient.Builder` 注入 |
| 多数据源 | `ConnectionProvider` / `JoyaClientRegistry` | ✅ | ✅ | 连接层 |
| 事务 | `TxEngine` / `TxSession` | ✅ | ✅ | 事务生命周期 |
| SQL 执行器替换 | `Executor.Factory`（`JoyaClient.Builder.executorFactory`） | ✅ | ✅ | 执行端口；事务视图会用事务连接来源重新创建 |

## 3. 注册方式

```java
JoyaClient.builder(dataSource)
    .rewriter(new TenantRewriter("tenant_id"))
    .executionListener(new SlowSqlListener())
    .entityListener(new AuditListener())
    .exceptionTranslator(new SqlStateTranslator())
    .rowMapperFactory(customFactory)
    .build();
```

### Spring Boot 自动装配

`joya-spring-boot-starter` 会按类型自动发现下列 Bean 并注册到每个 `JoyaClient`，**无需手写 `JoyaClientCustomizer`**：

| 扩展点 | 类型 | 路由规则 |
|---|---|---|
| `SqlRewriter` / `SqlExecutionListener` / `EntityListener` | 多值 | 无 `@Qualifier` → 所有客户端；`@Qualifier("order")` → 仅 `order` 客户端；两者叠加生效 |
| `RowMapperFactory` / `SqlExceptionTranslator` | 单值 | 限定为本客户端的 Bean 优先，否则取全局 Bean；同一级别出现多个候选时启动失败并给出明确提示 |

装配发生在 `JoyaClientCustomizer` 之前，customizer 仍可对 `Builder` 做最后覆盖。

> **FactoryBean 形式的扩展点**：类型探测允许 eager init（`getBeanNamesForType(type, false, true)`），
> 以 `FactoryBean` 声明、`getObjectType()` 依赖初始化才能确定的实现同样会被发现；单值扩展点在 Bean 正在创建时返回惰性代理。

> **SQL 明文日志脱敏**：同时开启 `joya.sql-log` 与 `joya.sql-log-print-args` 时，参数值会按逻辑名匹配
> `joya.sql-log-redact-patterns`（默认覆盖 password / passwd / secret / token / credential）后替换为 `***`；
> 非法正则在启动期直接失败，不会带着未脱敏日志运行。

所有扩展点实现 `Ordered`：`order()` 升序，同值按 `Class.getName()` 字典序；排序在首次执行时惰性完成一次。
这样容器启动期因循环依赖注入的惰性代理不会以伪造的 `order()` 参与排序。

> **分页 / count 的双视图观测语义**：`COUNT(*)` 包装与 `LIMIT / OFFSET`（或 `OFFSET ... FETCH`）由 `Dialect` 在 `SqlRewriter` **之后**生成，
> 方言分页语法仅属于 `kernel.dialect`，不进入 `SqlRewriter` 输入。进入 `SqlExecutionListener` 时，`ctx.statement()` 始终是**实际送入 JDBC 的物理语句**；
> 分页场景（`ctx.inPage() == true`）可通过 `ctx.logicalStatement()` 读取改写完成但未包装的逻辑语句（含原始 `ast()`），并通过 `ctx.offset()` / `ctx.limit()` 获取分页坐标。

## 4. 批量语义

- `SqlRewriter` 看到的是 `SqlNode`，不是 probe 字符串；
- v1 批量 INSERT 支持**整批同值**的额外列/参数（租户 ID、审计时间）；UPDATE/DELETE 的逐行绑定待接入；
- 逐行不同值的改写显式拒绝，抛 `SqlRewriteException`，避免静默错绑；
- **批量与单条一样完整经过 `prepare` 管线**：注入 `dbType` → `SqlRewriter`，不存在旁路；
- 批量 rewriter 只允许在列尾/参数尾追加；重排、删除或替换既有列与参数会被拒绝；
- `SqlExecutionListener` 通过 `ctx.batch()` 识别批量，`ctx.attributes().get(SqlExecutionContext.ATTR_BATCH_ROWS)` 获取行数。

## 5. 与旧模型的关系

| 旧 | 新 | 兼容策略 |
|---|---|---|
| `SqlInterceptor` 做租户/软删/分表 | `SqlRewriter` | **已删除文本拦截层**；结构变更全部迁移到 AST |
| `SqlInterceptor` 做日志 | `SqlExecutionListener` | starter 的 SQL 日志已改为 listener |
| `TableNameResolver` 与拦截器都改表名 | `TableNameResolver` 唯一 | `SqlRewriter` 不提供改表 API |
| `EntityListener` 10 处 `instanceof` | `EntityLifecycle` | 实体自身实现接口仍有效 |
| 无执行观测 | `SqlExecutionListener` | 新增 |
| 无异常分类 | `SqlExceptionTranslator` | 新增 |
