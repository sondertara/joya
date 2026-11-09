# 沟通规则

所有与用户的交流、代码注释与提交说明一律使用**中文**。仅在以下情况可保留英文：API 名称、代码标识符、第三方库引用、外部示例文本。

---

# Joya Core — AGENTS.md

Joya 是一款面向现代 Java（JDK 17+）的轻量级 ORM 与流式 SQL 框架，以**统一流式 API（`DataOps`）**、**三段式类型安全查询 DSL（`Query`）**与**显式值事务（`Tx`）**为核心设计理念。

## 模块结构

```
joya-parent                             父 POM，聚合以下模块
├── joya-core                           核心 ORM + 流式查询与显式事务引擎
└── joya-spring-boot-starter            Spring Boot 自动配置
```

## joya-core 分层与包结构

所有核心代码位于 `com.sondertara.joya.core`，遵循单向依赖架构：
**API 门面层 (`core/`) → 语句渲染层 (`query/` + `statement/` + `metadata/`) → 执行内核层 (`kernel/`) → 基础层 (`naming/` + `utils/` + `exceptions/`)**，
`jdbc/` 位于内核之上（提供行映射与 Mapper 适配）。`core/`、`tx/`、`mapper/` 同属**门面簇**（`JoyaClient → TxStarter`、`Tx extends DataOps`、`JoyaMapper → DataOps` 天然互依，1 Tx = 1 Connection），除此之外**包级不允许任何循环依赖**。

依赖方向由 `ArchitectureGuardTest` 以**白名单**强制，并以 Tarjan 强连通分量检测包级循环（仅豁免上述门面簇）：
`kernel/**` 只允许依赖 `kernel/`、`naming/`、`utils/`、`exceptions/`；
`naming/**`、`model/**`、`function/**`、`exceptions/**` 不得依赖任何其他项目包；`utils/**` 只允许依赖 `utils/`、`exceptions/`、`function/`。

```
core/
├── DataOps.java                        统一数据操作契约：from(Class<T>) / of(Class<T>) / sql(...) / mapper(Class<M>) / withConnection / script（逃生舱在 JoyaClient 上用自动提交连接，在 Tx 上用事务连接）
├── DataOpsSupport.java                 DataOps 公共装配委托（JoyaClient 并发缓存模式与 TxImpl 单线程 + I9 所有者线程守卫模式共用）
├── JoyaClient.java                     单数据源客户端门面（1 JoyaClient = 1 DataSource；所有构造入口收敛于 Builder），暴露 from / of / sql / mapper / tx / txCall / script / withConnection
├── JoyaSettings.java                   实例级客户端配置（组合 ExecutionOptions + NamingPolicy）
├── EntityOps.java                      实体域门面：命令式写（insert/update/patch/save/delete）+ 列级变更构建器（insert/update/delete）+ 按主键快捷读
├── EntityInsert.java                   实体流式插入构建器（value / values / ignoreNulls → execute）
├── EntityUpdate.java                   实体流式条件更新构建器（set / where / allowAll → execute）
├── EntityDelete.java                   实体流式条件删除构建器（where / allowAll → execute）
├── SqlOps.java                         参数化原生 SQL 流式门面（list / listMap / one / first / value / count / page / stream / update / insert / batch）
├── EntityFinder.java                   实体读取（findById / findByIds / findAll / existsById / count）
├── EntityDeleter.java                  实体删除（deleteById / deleteByIds / delete / deleteAll / truncate）
├── EntityWriter.java                   实体写执行器（insert / insertAll / update / patch / updateAll / patchAll；EntityOps 与 UpsertPlanner 共用）
├── UpsertPlanner.java                  先查后写 / 原生 upsert 策略（save / saveAll；依赖 EntityWriter + EntityFinder，不回调门面）
├── Partitions.java                     集合分片与 IN 子句上限（写/删/查共用一份）
├── mapper/                             Mapper 门面（JoyaMapper 接口 / JoyaRepository 基类 / JdkMapperProxy）
├── listener/                           实体生命周期扩展（EntityListener 监听器契约 + EntityLifecycle 统一派发，与 JDBC 解耦）
├── InsertPlan.java                     实体插入计划（单条/批量共用；每实体一次快照、列选择、校验、生成键回写）
├── UpdatePlan.java                     实体更新计划（单条/批量/部分列共用的执行入口）
│
├── query/                              三段式类型安全查询 DSL 与延迟绑定渲染引擎
│   ├── ColumnMetaProvider.java         惰性列元数据提供器（API 期不翻译别名，按无捕获 Getter Class 二级缓存）
│   ├── SqlPredicate.java               延迟求值的 SQL 谓词节点接口
│   ├── OnPredicate.java                JOIN ON 附加条件契约（WhereCriterion 实现；query 根包不反向依赖 criterion）
│   ├── JoinType.java                   关联类型枚举
│   ├── Expr.java                       类型化 SQL 表达式列接口（extends IGetter<Object, R>；位于 query 根包以避免 query ↔ dsl 循环）
│   ├── RenderContext.java              运行期统一 SQL 渲染上下文（多表别名模式 / 单表无别名模式 + 表归属防笛卡尔积校验）
│   ├── dsl/
│   │   ├── Query.java                  统一查询流：from(T) → [join/where/groupBy/having/orderBy/limit/distinct/apply] → [.select(cols...)] → 终结方法
│   │   ├── Select1.java .. Select8.java, SelectN.java  定长泛型投影句柄（支持单列直取、list(X.class) 按名字映射、list(X::new) 按位置构造映射）
│   │   ├── Exprs.java                  类型化 SQL 表达式工厂（max / min / sum / avg / count / countDistinct / raw，产出 query.Expr）
│   │   ├── Projections.java            4 类投影策略（auto / entity / named / single / positional）与 RecordOrderVerifier 同类型列顺序校验
│   │   └── QueryCustomizer.java & QueryScope.java  外部查询扩展端口（隔离动态筛选协议）
│   ├── criterion/
│   │   ├── WhereCriterion.java         Lambda 类型安全 WHERE / HAVING 惰性条件构建器（可跨多表查询与单表改删复用）
│   │   └── JoinCriterion.java          Lambda 类型安全 JOIN 关系收集器
│   ├── pagination/                     PageRequest / PageQueryRequest / FilterCondition / SortCondition 等分页与动态筛选协议值类型（不依赖 dsl / criterion）
│   └── dynamic/                        PageQueryParams：把动态筛选协议适配为 QueryCustomizer（强制实体元数据白名单防注入校验）
│
├── tx/                                 显式值事务模块（1 Tx = 1 Connection，零 ThreadLocal 污染）
│   ├── Tx.java                         事务句柄（extends DataOps, AutoCloseable）：savepoint / savepointCall / afterCommit / afterCompletion / commit / rollback
│   ├── TxImpl.java                     唯一的 Tx 实现（由 TxStarter 包装 TxSession 产出；构造时通过 kernel.bind(TransactionalConnectionProvider) 绑定事务视图）
│   ├── TxSession.java                  底层事务会话 SPI（TxConnection 与 SpringTxSession 实现；屏蔽物理事务差异）
│   ├── TxSessionSupport.java           事务会话公共支撑（I9 所有者线程守卫、deadline 计时与剩余秒数换算、泛型保存点栈）
│   ├── TxStarter.java                  不可变事务配置与启动器：isolation / readOnly / timeout / retryOnDeadlock → run / call / begin
│   ├── TxConnection.java               单库物理连接状态机（实现 TxSession，组合 TxSessionSupport 管理保存点栈、deadline 超时与现场还原）
│   ├── TxOptions.java                  不可变事务选项
│   ├── TxEngine.java                   事务引擎扩展点 SPI：open(kernel, options) 只产出 TxSession，连接只经 ConnectionProvider 借还（TxEngine.JDBC / SpringTxEngine 实现）
│   ├── Isolation.java                  事务隔离级别枚举
│   └── TxOutcome.java                  事务终态枚举（COMMITTED / ROLLED_BACK / UNKNOWN）
│
├── kernel/                             执行内核（只依赖 kernel / naming / utils / exceptions）
│   ├── ExecutionKernel.java            唯一执行入口（仅接收 SqlStatement / BatchSqlStatement，支持 bind(ConnectionProvider) 派生事务视图；执行委托 Executor 端口，观测委托 ExecutionObserver）
│   ├── StatementPreparer.java          语句准备管线（包内）：注入 dbType → SqlRewriter（AST）→ SqlRenderer，单条与批量共用，无旁路
│   ├── connection/
│   │   ├── ConnectionProvider.java     连接获取策略端口（acquire / release / discard / queryTimeoutSeconds / isExternallyManaged / allowsTransactionControl / originDataSource）
│   │   ├── ConnectionCallback.java     连接级逃生回调端口
│   │   ├── AutoCommitFlipTracker.java  非自动提交连接池借还时的 autoCommit 临时切换与还原追踪器
│   │   └── DataSourceConnectionProvider.java  池化自动提交连接提供者
│   ├── executor/                       执行端口与默认 JDBC 适配器
│   │   ├── ExecutionOptions.java       执行期配置（超时 / 批大小）
│   │   ├── Executor.java               SQL 执行端口（query / queryList / queryStream / update / batchExecute）与 Executor.Factory（可替换接缝，bind 事务视图时按新连接来源重建）
│   │   ├── JdbcExecutor.java           默认实现（借连接 → 执行 → 还连接）
│   │   ├── RowMapper.java             行映射端口
│   │   ├── RowMapperFactory.java      结果类型 → 行映射器解析端口
│   │   ├── ResultType.java            结果类型分类（SCALAR/BEAN/RECORD/MAP/UNSUPPORTED）
│   │   ├── ResultSetExtractor.java    结果集提取端口
│   │   └── BatchSlice.java            批量执行内核值类型（计数 + 生成键）
│   ├── interceptor/                    SqlRewriter / SqlRewriteContext / SqlRewriteException
│   ├── observe/                        SqlExecutionListener / SqlExecutionContext / SqlExecutionResult / ExecutionObserver（监听器编排）
│   ├── error/                          SqlExceptionTranslator
│   ├── statement/                      SqlStatement / BatchSqlStatement / SqlRenderer（唯一 SQL 文本组装点）/ SqlFormatUtils（占位符词法扫描）/ 参数契约 / DbType / UpsertStyle（语句词汇表，dialect 与 executor 单向依赖它）
│   ├── dialect/                        Dialect / DialectStrategy / PaginationStyle / QuoteStyle
│   └── support/                        Ordered（有序扩展点标记接口）/ ScriptRunner / LoggingOutputStream
│
├── statement/                          语句构建层
│   └── MutationContext.java            变更操作上下文（metadata + values + where + naming）
│
├── statement/factory/                  语句工厂（只决定列与参数，不写 SQL 文本）
│   ├── SelectStatementFactory.java     单表按列投影 SELECT（WHERE 与 Query 同走 toNode AST 路径）
│   ├── InsertStatementFactory.java     INSERT 渲染
│   ├── UpsertStatementFactory.java     原生 UPSERT 渲染（包装 INSERT 结构树，单条与批量）
│   ├── UpdateStatementFactory.java     UPDATE 渲染（强制非空 WHERE 或显式 allowAll）
│   └── DeleteStatementFactory.java     DELETE 渲染（强制非空 WHERE 或显式 allowAll）
│
├── metadata/                           只读实体元数据与操作快照
│   ├── PersistenceAnnotations.java     持久化注解双栈解析器（jakarta + javax）
│   ├── EntityMetadataContext.java      不可变实体表结构元数据（保留注解原文）
│   ├── LocalTableMetaCache.java        基于 JDK ClassValue 的无锁实体元数据缓存
│   └── EntitySnapshot.java             单次操作实体字段值快照
│
├── function/                           类型化列引用与转换契约（IGetter / TypedConverter / FieldAlias）
│
├── naming/                             命名与表名解析（零内部依赖）
│   ├── NamingPolicy.java               formatTable / formatColumn / maxJoinTable / resolveTable
│   └── TableNameResolver.java          配置期唯一表名入口（分表 / schema 切换；默认恒等）
│
├── result/                             变更结果值类型（MutationResult；只依赖 utils/exceptions）
│
└── jdbc/                               JDBC 结果集映射适配（只依赖 kernel/metadata/naming/model/function/utils/exceptions，不依赖 core 门面）
    └── resultset/mapper/               BeanRowMapper / MapRowMapper / DefaultRowMapperFactory
```

## 核心设计原则

1. **三段式统一查询流（`DataOps.from(T)`）与职责单一门面**：
   - `DataOps` 提供清晰的三大入口：`db.from(Entity.class)`（唯一查询 DSL 入口）、`db.of(Entity.class)`（实体写与按主键快捷读）、`db.sql(...)`（参数化原生 SQL）。
   - 所有查询遵循 **`from(T) [行过滤: join/where/groupBy/having/orderBy/limit] [.select(列...)] .终结方法(怎么装)`**：
     - 完整实体：`joya.from(User.class).where(..).list()`；
     - 部分列装回实体（单表与联表一致）：`joya.from(Order.class).join(..).select(Order::getId, Order::getAmount).list(Order.class)`；
     - 单列直取：`joya.from(User.class).select(User::getName).list()`；
     - 跨表/聚合按位置构造映射：`joya.from(Order.class).leftJoin(..).select(Order::getId, Order::getAmount, User::getName).page(OrderUserVO::new, PageRequest.of(0, 10))`（内置 `RecordOrderVerifier` 防同类型列顺序颠倒）；
     - 宽 DTO 自动按字段名推列：`joya.from(User.class).where(..).list(UserBrief.class)`；
     - 统计终结方法：`.count()`、`.exists()`、`.max(col)`、`.min(col)`、`.sum(col)`、`.avg(col)`。
   - `JoyaClient`（自动提交）与 `Tx`（显式事务）实现同一个 `DataOps` 接口，业务/仓储方法只需声明接收 `DataOps`，即可在自动提交或任意事务上下文中无缝复用。
2. **极简连接与显式值事务（1 `JoyaClient` = 1 `DataSource`，1 `Tx` = 1 `Connection`）**：
   - 彻底废除 `ThreadLocal` 事务上下文及伪分布式跨库事务包装，多数据源场景直接实例化多个对应的 `JoyaClient`。
   - 事务通过 `joya.tx(tx -> ...)`、`joya.txCall(tx -> ...)` 或 `joya.tx().isolation(...).run(tx -> ...)` 显式传递 `Tx` 值；局部回滚使用 `tx.savepoint(...)` / `tx.savepointCall(...)`。
3. **动态条件零样板（`WhereCriterion`）与默认防误操作**：
   - `WhereCriterion` 的比较条件默认**严格非空**：`eq` / `ne` / `lt` / `lte` / `gt` / `gte` / `between` / `contains` 传入 `null` 会立即抛 `IllegalArgumentException`（异常信息会提示改用 `isNull` 或 `*IfPresent`）。
   - 需要"值为空则跳过该条件"时，显式使用 `eqIfPresent` / `gtIfPresent` 等 `*IfPresent` 系列；空白字符串过滤使用 `containsIfNotBlank`；`in` / `notIn` 同样拒绝 `null` 或空集合，可选过滤使用 `inIfNotEmpty` / `notInIfNotEmpty`；`IS NULL` 使用显式 `isNull` / `isNotNull`。
   - `EntityUpdate` 与 `EntityDelete` 默认强制要求非空 `WHERE` 条件，全表操作必须显式调用 `.allowAll()`。
   - 外部动态筛选协议通过 `QueryCustomizer` 插件（`PageQueryParams.of(param)`）接入，强制执行标识符正则与实体元数据白名单校验，杜绝 SQL 注入。
   - 动态表名（按月分表 / 租户分表 / schema 切换）在**渲染期**由 `NamingPolicy.tableNameResolver` 决定：SQL 生成时就是最终表名，不存在执行期字符串改写。原生 SQL（`joya.sql(...)`）不携带表元数据，也不在该保证范围内。
4. **AST 改写无旁路与现代 Java 17 映射**：
   - `ExecutionKernel` 是所有 SQL 执行的唯一收口，所有查询、变更、原生 SQL 与批处理 100% 经过 `SqlRewriter`（仅框架生成的 AST）与 `SqlExecutionListener` 观测，并携带完整 `StatementMetadata`；文本级 `SqlInterceptor` 已删除，避免静默错绑参数。
5. **Spring Boot 3 旁路（Sidecar）与多数据源注册表（`joya-spring-boot-starter`）**：
   - **主库复用与扩展库旁路隔离（`JoyaAutoConfiguration` → `JoyaClientRegistry`）**：主库通过 `joya.primary` 配置（默认 `use-spring=true` 复用 Spring 容器主 `DataSource`），扩展库通过 `joya.datasources` 数组独立初始化并登记到 `JoyaClientRegistry`（不注册全局 `DataSource` Bean，不干扰主 ORM）；`SqlRewriter` / `SqlExecutionListener` / `EntityListener` 自动发现并按 `@Qualifier("dsName")` 路由到指定数据源客户端（无限定符的 Bean 对所有客户端生效），`RowMapperFactory` / `SqlExceptionTranslator` 为单例型扩展点（限定本库优先，同级多个则启动失败）。
   - **Spring 事务无缝融入（`SpringConnectionProvider` + `SpringTxEngine`）**：当容器中存在与主 `DataSource` 匹配的 `DataSourceTransactionManager` 或已显式绑定同一 `DataSource` 的 `JpaTransactionManager` 时，通过 `DataSourceUtils` 与其协同，使 `joya` 操作、`joya.tx(...)`（含 `savepoint`）与 `@Transactional` 及主 ORM 共享同一条事务连接；若无匹配的事务管理器（如未绑定 `DataSource` 的 JPA 管理器或资源键不一致），则输出启动告警并回退为纯 JDBC 事务引擎（`DataSourceConnectionProvider` + `TxEngine.JDBC`）。
   - **`JoyaMapper` 免扫描随取随用**：通过 `joya.mapper(XxxMapper.class)` / `tx.mapper(XxxMapper.class)` 直接按需获取绑定到对应客户端或事务上下文的 Mapper 代理，零包扫描开销。
