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
**API 门面层 (`core/`) → AST 构建层 (`query/` + `metadata/`) → 执行内核与单趟编译层 (`kernel/`) → 基础层 (`naming/` + `utils/` + `exceptions/`)**，
`jdbc/` 位于内核之上（提供行映射与 Mapper 适配）。`core/`、`tx/`、`mapper/` 同属**门面簇**（`JoyaClient → TxStarter`、`Tx extends DataOps`、`JoyaMapper → DataOps` 天然互依，1 Tx = 1 Connection），除此之外**包级不允许任何循环依赖**。

依赖方向由 `ArchitectureGuardTest` 以**白名单**强制，并以 Tarjan 强连通分量检测包级循环（仅豁免上述门面簇）：
`kernel/**` 只允许依赖 `kernel/`、`naming/`、`utils/`、`exceptions/`；
`naming/**`、`function/**`、`exceptions/**` 不得依赖任何其他项目包；`utils/**` 只允许依赖 `utils/`、`exceptions/`、`function/`。

```
core/
├── DataOps.java                        统一数据操作契约：from(Class<T>) / from(Query<T>) / of(Class<T>) / sql(...) / mapper(Class<M>) / withSettings / withConnection / script（派生只读视图不污染原实例；逃生舱在 JoyaClient 上用自动提交连接，在 Tx 上用事务连接，均禁止直接调用事务控制方法）
├── DataOpsSupport.java                 DataOps 公共装配委托（JoyaClient 并发缓存模式与 TxImpl 单线程 + I9 所有者线程守卫模式共用）
├── JoyaClient.java                     单数据源客户端门面（1 JoyaClient = 1 DataSource；所有构造入口收敛于 create / builder），暴露 from / of / sql / mapper / withSettings / tx / txCall / script / withConnection
├── JoyaSettings.java                   实例级客户端配置（组合 ExecutionOptions + NamingPolicy，支持 toBuilder() 增量修改）
├── EntityOps.java                      实体域门面：命令式写（insert/update/patch/save/delete）+ 列级变更构建器（insert/update/delete）+ 按主键快捷读
├── EntityInsert.java                   实体流式插入构建器（value / valueIfPresent / values / ignoreNulls → execute）
├── EntityUpdate.java                   实体流式条件更新构建器（set / setIfPresent / incr / setExpr / where / allowAll → execute）
├── EntityDelete.java                   实体流式条件删除构建器与清空入口（where / allowAll → execute + truncate）
├── SqlOps.java                         参数化原生 SQL 与预构建物理 SqlStatement 流式门面（bind / bindMap / bindBean 具名参数绑定 + list / listMap / one / first / value / count / page / stream / update / insert / batch）
├── EntityFinder.java                   实体读取（findById / findByIds / findAll / existsById / count；直接构建 AstStatement）
├── EntityStatements.java               包内无状态实体 DML/SELECT AST 组装器（统一产出 AstStatement / BatchAstStatement，不拼接 SQL 文本）
├── InsertPlan.java                     实体插入计划（单条/批量共用；基于扁平 Object[] 快照完成列选择、非空校验、AstStatement/BatchAstStatement 构建与生成键回写）
├── UpdatePlan.java                     实体更新计划（单条/批量/部分列共用；基于扁平 Object[] 快照直接产出 AstStatement/BatchAstStatement）
├── UpsertPlanner.java                  先查后写 / 原生 upsert 策略（save / saveAll；直接编排 InsertPlan + UpdatePlan + EntityFinder，不回调门面）
├── Partitions.java                     集合分片与 IN 子句上限（写/删/查共用一份）
├── mapper/                             Mapper 门面（JoyaMapper 接口 / JoyaRepository 基类 / JdkMapperProxy）
├── listener/                           实体级前置自监听契约（EntityListener：无参 prePersist / preUpdate，零项目内外部包依赖）
│
├── query/                              三段式类型安全查询 DSL 与纯语义作用域解析层
│   ├── ColumnMetaProvider.java         惰性列元数据提供器（API 期不翻译别名，按无状态 Getter Class 二级缓存）
│   ├── SqlPredicate.java               延迟求值的 SQL 谓词节点接口（直接编译为 SqlNode）
│   ├── OnPredicate.java                JOIN ON 附加条件契约（WhereCriterion 实现；query 根包不反向依赖 criterion）
│   ├── JoinType.java                   关联类型枚举（映射为 SqlNode.JoinKind）
│   ├── Expr.java                       密封类型化 SQL 表达式列接口（sealed: ColumnExpr / AggregateExpr / RawExpr，直接产出 SqlNode）
│   ├── TableScope.java                 纯语义表作用域与别名绑定上下文（多表别名模式 / 单表无别名模式 + 表归属防笛卡尔积校验，零 SQL 拼接）
│   ├── dsl/
│   │   ├── Query.java                  统一查询流：from(T) → [join/where/groupBy/having/orderBy/limit/distinct/apply] → [.select(cols...)] → 终结方法
│   │   ├── QueryAstBuilder.java        纯 AST 查询组装器（将 Query 状态与 TableScope 直接编译为 AstStatement）
│   │   ├── Select1.java .. Select8.java, SelectN.java  定长泛型投影句柄（支持单列直取、list(X.class) 按名字映射、list(X::new) 按位置构造映射）
│   │   ├── Exprs.java                  类型化 SQL 表达式工厂（max / min / sum / avg / count / countDistinct / raw，产出结构化 query.Expr）
│   │   ├── Projections.java            4 类投影策略（auto / entity / named / single / positional）与 RecordOrderVerifier 同类型列顺序校验
│   │   └── QueryCustomizer.java & QueryScope.java  外部查询扩展端口（隔离动态筛选协议）
│   ├── criterion/
│   │   ├── WhereCriterion.java         Lambda 类型安全 WHERE / HAVING 惰性条件构建器（可跨多表查询与单表改删复用）
│   │   └── JoinCriterion.java          Lambda 类型安全 JOIN 关系收集器
│   ├── pagination/                     PageRequest / PageQueryRequest / FilterGroup / FilterCondition / SortCondition 等分页与动态筛选协议值类型（全链路统一 0-based page + size；不依赖 dsl / criterion）
│   └── dynamic/                        PageQueryParams：把动态筛选协议适配为 QueryCustomizer（强制实体元数据白名单防注入校验）
│
├── tx/                                 显式值事务模块（1 Tx = 1 Connection，零 ThreadLocal 污染）
│   ├── Tx.java                         事务句柄（extends DataOps, AutoCloseable）：savepoint / savepointCall / afterCommit / afterCompletion / commit / rollback
│   ├── TxImpl.java                     唯一的 Tx 实现（由 TxStarter 包装 TxSession 产出；构造时通过 kernel.bind(TransactionalConnectionProvider) 绑定事务视图）
│   ├── TxSession.java                  底层事务会话 SPI（TxConnection 与 SpringTxSession 实现；屏蔽物理事务差异）
│   ├── TxSessionSupport.java           事务会话公共支撑（I9 所有者线程守卫、nanoTime 防回绕 deadline 计时与到期快速失败、泛型保存点栈）
│   ├── TxStarter.java                  不可变事务配置与启动器：isolation / readOnly / timeout / requiresNew / retryOnDeadlock → run / call / begin
│   ├── TxConnection.java               单库物理连接状态机（实现 TxSession，组合 TxSessionSupport 管理保存点栈、deadline 超时与现场还原）
│   ├── TxOptions.java                  不可变事务选项
│   ├── TxEngine.java                   事务引擎扩展点 SPI：open(kernel, options) 只产出 TxSession，连接只经 ConnectionProvider 借还（TxEngine.JDBC / SpringTxEngine 实现）
│   ├── Isolation.java                  事务隔离级别枚举
│   └── TxOutcome.java                  事务终态枚举（COMMITTED / ROLLED_BACK / UNKNOWN）
│
├── kernel/                             执行内核（只依赖 kernel / naming / utils / exceptions）
│   ├── ExecutionKernel.java            唯一执行入口（双轨接收结构化 AstStatement/BatchAstStatement 与物理 SqlStatement/BatchSqlStatement，支持 bind(ConnectionProvider) 派生事务视图；执行委托 Executor 端口，观测委托 ExecutionObserver）
│   ├── StatementPreparer.java          语句准备管线（包内）：注入 dbType → SqlRewriter（AST → AST）→ SqlRenderer 单趟编译物理语句与批槽位，无二次渲染无旁路
│   ├── connection/
│   │   ├── ConnectionProvider.java     连接获取策略端口（acquire / release / discard / queryTimeoutSeconds / isExternallyManaged / allowsTransactionControl / originDataSource）
│   │   ├── ConnectionCallback.java     连接级逃生回调端口
│   │   ├── AutoCommitFlipTracker.java  非自动提交连接池借还时的 autoCommit 临时切换与还原追踪器
│   │   └── DataSourceConnectionProvider.java  池化自动提交连接提供者
│   ├── executor/                       执行端口与默认 JDBC 适配器
│   │   ├── ExecutionOptions.java       执行期配置（超时 / 批大小）
│   │   ├── Executor.java               SQL 执行端口（query / queryList / queryStream / update / batchExecute / batchExecuteExact）与 Executor.Factory（可替换接缝，bind 事务视图时按新连接来源重建）
│   │   ├── JdbcExecutor.java           默认实现（借连接 → 执行 → 还连接；forResultSet 单次元数据解析与流式早释放；batchExecuteExact 单连接复用 PS 逐行返回精确行计数供 @Version 乐观锁使用）
│   │   ├── RowMapper.java              行映射端口（支持 throws SQLException 统一纳入异常翻译器与 forResultSet 热路径预绑定）
│   │   ├── RowMapperFactory.java       结果类型 → 行映射器解析端口
│   │   ├── ResultType.java             结果类型分类（SCALAR/BEAN/RECORD/MAP/UNSUPPORTED）
│   │   ├── ResultSetExtractor.java     结果集提取端口
│   │   └── BatchSlice.java             批量执行内核值类型（计数 + 生成键）
│   ├── interceptor/                    SqlRewriter / SqlRewriteContext / SqlRewriteException
│   ├── observe/                        SqlExecutionListener / SqlExecutionContext / SqlExecutionResult / ExecutionObserver（监听器编排）
│   ├── error/                          SqlExceptionTranslator
│   ├── statement/                      SqlNode（AST 2.0 强类型节点与密封 Param.Binding）/ SqlNodes / SqlNodeTransformer（不可变 Shuttle Visitor）/ AstStatement / BatchAstStatement / SqlStatement / BatchSqlStatement / BatchSlot / SqlRenderer（全仓唯一单趟流式 SQL 与参数/批槽位编译器）/ SqlFormatUtils / SqlScriptSplitter / 参数契约 / DbType / UpsertStyle
│   ├── dialect/                        Dialect / DialectStrategy（单飞双重检查缓存探测）/ PaginationStyle / QuoteStyle
│   └── support/                        Ordered（有序扩展点标记接口）/ ScriptRunner / LoggingOutputStream
│
├── metadata/                           只读实体元数据
│   ├── PersistenceAnnotations.java     持久化注解双栈解析器（jakarta + javax，含 @Convert / AttributeConverter 与 @Version）
│   ├── ColumnConverter.java            双向列值转换器（封装标准 JPA AttributeConverter，预绑定 MethodHandle invokeExact）
│   ├── VersionMetadata.java            乐观锁 @Version 元数据（整型校验、初值 0 生成与防溢出递增）
│   ├── EntityMetadataContext.java      不可变实体表结构元数据（预编译 MethodHandle 读写句柄、规范列名与列可写校验、扁平 Object[] 快照、列转换器与版本列元数据）
│   └── LocalTableMetaCache.java        基于 JDK ClassValue 的无锁实体元数据缓存
│
├── function/                           类型化列引用契约（IGetter）
│
├── naming/                             命名与表名解析（零内部依赖）
│   ├── NamingPolicy.java               formatTable / formatColumn / maxJoinTable / resolveTable
│   └── TableNameResolver.java          配置期唯一表名入口（分表 / schema 切换；默认恒等）
│
├── result/                             变更结果值类型（MutationResult；只依赖 utils/exceptions）
│
└── jdbc/                               JDBC 结果集映射适配（只依赖 kernel/metadata/naming/function/utils/exceptions，不依赖 core 门面）
    └── resultset/mapper/               BeanRowMapper（预适配 invokeExact + final 安全发布）/ MapRowMapper / DefaultRowMapperFactory
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
4. **AST 2.0 单趟编译无旁路与现代 Java 17 映射**：
   - `Query` 与实体 DML 前端只产出纯结构化 `AstStatement` / `BatchAstStatement`（携带未加引号规范列名），进入 `ExecutionKernel` 后先经过 `SqlRewriter`（`AST → AST`，可组合 `SqlNodeTransformer`），再由 `SqlRenderer` 按 `NamingPolicy` 在**单个 `StringBuilder` 流式单趟编译**为物理 `SqlStatement` / `BatchSqlStatement`（全程仅渲染 1 次，改写器注入的新列自动继承客户端大小写与引号策略）。
   - `ExecutionKernel` 是所有 SQL 执行的唯一收口，常规查询、变更、原生 SQL 与批处理 100% 经过 `SqlExecutionListener` 观测（`ctx.statement()` 为实际送入 JDBC 的物理语句，分页场景经 `ctx.logicalStatement()` / `offset()` / `limit()` 同步暴露逻辑视图与坐标），并携带完整 `StatementMetadata`；底层连接级逃生舱 `withConnection` 与轻量初始化脚本 `script` 显式旁路改写与观测管线。
5. **标准 JPA `@Convert` 与 `@Version` 乐观锁一等支持**：
   - 废除自定义 `@FieldAlias` / `TypedConverter`，统一采用标准 JPA `@Convert(converter = ...)` + `AttributeConverter<X, Y>`（`jakarta` / `javax` 双栈无反射热路径），贯穿实体增删改查、Upsert、`WhereCriterion`、`BeanRowMapper` 与单列/位置投影；并内置 JSR-310 `Instant` JDBC 双向读写兼容。
   - `@Version`（支持 `short/Short/int/Integer/long/Long`）覆盖全生命周期：`insert` / `insertAll` / `EntityInsert` 自动初始化 `null` 为 `0` 并回写实体；单条与批量更新/删除统一注入 `WHERE pk = ? AND version = ?`，批量走 `batchExecuteExact` 消除 JDBC `SUCCESS_NO_INFO (-2)` 导致的静默冲突漏判，并采用**两阶段内存版本号回写**（全部数据库行确认无冲突后才推进实体内存版本）；`save`/`saveAll` 对 `@Version` 实体自动禁用原生 UPSERT 改为先查后写；`deleteById`/`deleteByIds` 保持按主键强制删除语义。
6. **Spring Boot 3 主 ORM 与旁路多数据源一体（`joya-spring-boot-starter`）**：
   - **开箱即用主 ORM 依赖与时序（`JoyaAutoConfiguration` → `JoyaClientRegistry` / `JoyaNamedClientsAutoConfiguration`）**：默认引入 `spring-boot-starter-jdbc`（HikariCP + JDBC + Tx）与非可选 `jakarta.persistence-api`；`JoyaAutoConfiguration` 严格排在 `DataSource` / `DataSourceTransactionManager` / `HibernateJpa` / `Jta` / `Flyway` / `Liquibase` 之后、`TransactionAutoConfiguration` 之前；主库默认 `use-spring=true` 复用 Spring 主 `DataSource`，设为 `use-spring=false` 自建主库且无外部事务管理器时自动暴露 `@Primary transactionManager` 并触发 `@EnableTransactionManagement`；扩展库支持 `expose-transaction-manager=true` 暴露非 `@Primary` `<name>TransactionManager`（自动把容器已有唯一默认 TM 提升为 `@Primary` 保护无限定 `@Transactional`）。
   - **Spring 事务、指标与 Micrometer Observation 可观测性（`SpringConnectionProvider` + `SpringTxEngine` + `JoyaActuatorAutoConfiguration` + `JoyaObservationAutoConfiguration`）**：与 `DataSourceTransactionManager` / `JpaTransactionManager` 经 `DataSourceUtils` 共享连接并支持 `tx().requiresNew()`；`JoyaActuatorAutoConfiguration` 为自建旁路库注册 `joyaHealthContributor` 并将 `MicrometerMetricsTrackerFactory` 自动绑定到自建 `HikariDataSource`；`JoyaObservationAutoConfiguration` 在存在 `ObservationRegistry` 时自动桥接 SQL 执行指标与链路追踪（零 `ThreadLocal` 泄漏，流式查询延后至 `onStreamClosed` 收口）。
   - **`JoyaMapper` 免扫描即用、`@JoyaMapperScan` 与 `@JoyaTest` 数据层切片**：随时通过 `joya.mapper(XxxMapper.class)` / `tx.mapper(XxxMapper.class)` 获取，也可通过 `@JoyaMapperScan` 直接注入并参与 `@Transactional`；提供对标 `@JdbcTest` 的 `@JoyaTest` 测试切片注解（仅装配数据源、事务、迁移与 Joya 客户端，自动排除 `@Service`/`@Controller` 并默认 `@Transactional` 回滚）。
