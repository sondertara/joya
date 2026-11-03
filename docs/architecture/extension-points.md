# 扩展点模型（AST-lite）

> 本文描述 Joya 1.x 的 SQL 扩展点分层。目标：**每个关注点有且只有一个推荐入口**，单条与批量语义一致。

## 1. 管线

```
Build     Query / Plan / Factory  →  SqlNode（内部 AST）
Rewrite   SqlRewriter（有序）      →  SqlNode（结构改写：租户、软删、审计列）
Render    SqlRenderer              →  SqlStatement（SQL + 参数 + metadata）
Intercept SqlInterceptor（兼容层）  →  SqlStatement（仅文本微调）
Execute   ExecutionKernel → Executor
Observe   SqlExecutionListener     →  日志 / 指标 / 慢查询
```

- **AST 只覆盖 Joya 生成的 SQL**；`joya.sql("...")` 原生 SQL 保持字符串，不解析。
- `SqlStatement.ast()` 为 `null` 时，`SqlRewriter` 自动跳过该语句。

## 2. 扩展点矩阵

| 关注点 | 推荐入口 | 单条 | 批量 | 说明 |
|---|---|---|---|---|
| 分表 / schema 切换 | `NamingPolicy.tableNameResolver` | ✅ | ✅ | 配置期唯一表名机制；`SqlRewriter` 不得改表名 |
| 租户过滤 | `SqlRewriter` | ✅ | ✅（INSERT 常量列） | 改 WHERE/追加列；不要用 `SqlInterceptor` 加参数 |
| 软删 | `SqlRewriter` | ✅ | ⏳（UPDATE/DELETE 逐行绑定待接入） | DELETE → UPDATE + WHERE |
| 审计字段 | `EntityListener`（经 `EntityLifecycle`） | ✅ | ✅ | 实体字段赋值，pre 阶段 |
| SQL 日志 / 耗时 / 指标 | `SqlExecutionListener` | ✅ | ✅ | before / after / onError |
| 错误分类 / 重试 | `SqlExceptionTranslator` | ✅ | ✅ | SQLState → 语义异常 |
| 动态筛选协议 | `QueryCustomizer` | ✅ | — | 查询构建期 |
| 结果映射 | `RowMapperFactory` | ✅ | ✅ | 可由 `JoyaClient.Builder` 注入 |
| 多数据源 | `ConnectionProvider` / `JoyaClientRegistry` | ✅ | ✅ | 连接层 |
| 事务 | `TxEngine` / `TxSession` | ✅ | ✅ | 事务生命周期 |
| SQL 执行器替换 | `Executor.Factory`（`JoyaClient.Builder.executorFactory`） | ✅ | ✅ | 执行端口；事务视图会用事务连接来源重新创建 |
| 最后一段文本微调（hint/注释） | `SqlInterceptor` | ✅ 仅改文本 | ✅ 仅改文本 | 兼容层，不用于结构变更；单条与批量均校验 `?` 占位符个数与参数数量一致 |

## 3. 注册方式

```java
JoyaClient.builder(dataSource)
    .rewriter(new TenantRewriter("tenant_id"))
    .executionListener(new SlowSqlListener())
    .entityListener(new AuditListener())
    .exceptionTranslator(new SqlStateTranslator())
    .rowMapperFactory(customFactory)
    .interceptor(legacyHintInterceptor)   // 兼容
    .build();
```

### Spring Boot 自动装配

`joya-spring-boot-starter` 会按类型自动发现下列 Bean 并注册到每个 `JoyaClient`，**无需手写 `JoyaClientCustomizer`**：

| 扩展点 | 类型 | 路由规则 |
|---|---|---|
| `SqlInterceptor` / `SqlRewriter` / `SqlExecutionListener` / `EntityListener` | 多值 | 无 `@Qualifier` → 所有客户端；`@Qualifier("order")` → 仅 `order` 客户端；两者叠加生效 |
| `RowMapperFactory` / `SqlExceptionTranslator` | 单值 | 限定为本客户端的 Bean 优先，否则取全局 Bean；同一级别出现多个候选时启动失败并给出明确提示 |

装配发生在 `JoyaClientCustomizer` 之前，customizer 仍可对 `Builder` 做最后覆盖。

所有扩展点实现 `Ordered`：`order()` 升序，同值按 `Class.getName()` 字典序，构建期排序一次。

> **分页 / count 的方言文本**：`COUNT(*)` 包装与 `LIMIT / OFFSET`（或 `TOP` / `FETCH`）由 `Dialect` 在拦截与改写**之后**生成，
> 因此 `SqlInterceptor` / `SqlExecutionListener` 看到的是未分页的语句文本；方言分页语法仅属于 `kernel.dialect`，不进入扩展点。

## 4. 批量语义

- `SqlRewriter` 看到的是 `SqlNode`，不是 probe 字符串；
- v1 批量 INSERT 支持**整批同值**的额外列/参数（租户 ID、审计时间）；UPDATE/DELETE 的逐行绑定待接入；
- 逐行不同值的改写显式拒绝，抛 `SqlRewriteException`，避免静默错绑；
- **批量与单条一样完整经过 `prepare` 管线**：注入 `dbType` → `SqlRewriter` → `SqlInterceptor`，不存在旁路；
- `SqlInterceptor` 收到的是整批共用的 SQL **模板探针**（参数为空，整批只触发一次，`ctx.isBatch()` 为 `true`，`ctx.attr(RewriteContext.ATTR_BATCH_ROWS)` 为行数）；
- 批量模式下 `SqlInterceptor` 只允许改写文本（hint / 注释）；追加参数或改变 `?` 占位符个数会抛 `UnsupportedOperationException`，异常信息指向 `SqlRewriter` 迁移，避免逐行绑定静默错位。

## 5. 与旧模型的关系

| 旧 | 新 | 兼容策略 |
|---|---|---|
| `SqlInterceptor` 做租户/软删/分表 | `SqlRewriter` | 保留 `SqlInterceptor` 仅文本微调 |
| `TableNameResolver` 与拦截器都改表名 | `TableNameResolver` 唯一 | 文档收敛；`SqlRewriter` 不提供改表 API |
| `EntityListener` 10 处 `instanceof` | `EntityLifecycle` | 实体自身实现接口仍有效 |
| 无执行观测 | `SqlExecutionListener` | 新增 |
| 无异常分类 | `SqlExceptionTranslator` | 新增 |
