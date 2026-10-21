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

所有核心代码位于 `com.sondertara.joya.core`，遵循严格的四层单向依赖架构：
**API 门面层 (`core/`) → 语句渲染层 (`statement/` + `query/`) → 执行内核层 (`kernel/` + `jdbc/`) ← 显式事务层 (`tx/`)**

```
core/
├── DataOps.java                        统一数据操作契约：from(Class<T>) / of(Class<T>) / sql(...) / mapper(Class<M>)
├── JoyaClient.java                     单数据源客户端门面（1 JoyaClient = 1 DataSource），暴露 from / of / sql / mapper / tx / txCall / script / withConnection
├── JoyaSettings.java                   实例级客户端配置（queryTimeoutSeconds / batchSize / maxJoinTable / columnUppercase / tableUppercase / schemaValidation）
├── EntityOps.java                      实体域门面：命令式写（insert/update/patch/save/delete）+ 列级变更构建器（insert/update/delete）+ 按主键快捷读
├── EntityInsert.java                   实体流式插入构建器（value / values / ignoreNulls → execute）
├── EntityUpdate.java                   实体流式条件更新构建器（set / where / allowAll → execute）
├── EntityDelete.java                   实体流式条件删除构建器（where / allowAll → execute）
├── SqlOps.java                         参数化原生 SQL 流式门面（list / listMap / one / first / value / count / page / stream / update / insert / batch）
│
├── query/                              三段式类型安全查询 DSL 与延迟绑定渲染引擎
│   ├── ColumnMetaProvider.java         惰性列元数据提供器（API 期不翻译别名，按无捕获 Getter Class 二级缓存）
│   ├── SqlPredicate.java               延迟求值的 SQL 谓词节点接口
│   ├── RenderContext.java              运行期统一 SQL 渲染上下文（多表别名模式 / 单表无别名模式 + 表归属防笛卡尔积校验）
│   ├── dsl/
│   │   ├── Query.java                  统一查询流：from(T) → [join/where/groupBy/having/orderBy/limit/distinct/apply] → [.select(cols...)] → 终结方法
│   │   ├── Select1.java .. Select8.java, SelectN.java  定长泛型投影句柄（支持单列直取、list(X.class) 按名字映射、list(X::new) 按位置构造映射）
│   │   ├── Expr.java & Exprs.java      类型化 SQL 表达式列（max / min / sum / avg / count / countDistinct / raw，extends IGetter<Object, R>）
│   │   ├── Projections.java            4 类投影策略（auto / entity / named / single / positional）与 RecordOrderVerifier 同类型列顺序校验
│   │   └── QueryCustomizer.java & QueryScope.java  外部查询扩展端口（隔离动态筛选协议）
│   ├── criterion/
│   │   ├── WhereCriterion.java         Lambda 类型安全 WHERE / HAVING 惰性条件构建器（可跨多表查询与单表改删复用）
│   │   └── JoinCriterion.java          Lambda 类型安全 JOIN 关系收集器
│   └── pagination/                     PageRequest、PageQueryParam 与 PageQueryParams 动态筛选适配器（强制实体元数据白名单防注入校验）
│
├── tx/                                 显式值事务模块（1 Tx = 1 Connection，零 ThreadLocal 污染）
│   ├── Tx.java                         事务句柄（extends DataOps, AutoCloseable）：savepoint / savepointCall / afterCommit / afterCompletion / commit / rollback
│   ├── TxImpl.java                     单连接事务实现（构造时直接通过 template.bind(txConnection) 绑定事务视图）
│   ├── TxStarter.java                  不可变事务配置与启动器：isolation / readOnly / timeout / retryOnDeadlock → run / call / begin
│   ├── TxConnection.java               单库物理连接状态机（实现 ConnectionProvider，管理保存点栈 Deque<Savepoint>、deadline 超时与现场还原）
│   ├── TxOptions.java                  不可变事务选项
│   ├── Isolation.java                  事务隔离级别枚举
│   └── TxOutcome.java                  事务终态枚举（COMMITTED / ROLLED_BACK）
│
├── kernel/                             无状态执行内核（零业务/实体依赖）
│   ├── JoyaTemplate.java               唯一执行与拦截器链入口（仅接收 SqlStatement / BatchSqlStatement，支持 bind(ConnectionProvider) 派生事务视图）
│   ├── connection/
│   │   ├── ConnectionProvider.java     连接获取策略端口（acquire / release / queryTimeoutSeconds）
│   │   └── DataSourceConnectionProvider.java  池化自动提交连接提供者（每次操作即借即还，支持默认 queryTimeoutSeconds）
│   ├── tx/
│   │   └── TxEngine.java               事务引擎扩展点 SPI（纯 JDBC 使用 JdbcTxEngine，Spring 环境使用 SpringTxEngine）
│   ├── interceptor/
│   │   ├── SqlInterceptor.java         SQL 改写拦截器接口
│   │   ├── RewriteContext.java         改写上下文
│   │   └── DefaultChain.java           责任链实现
│   ├── statement/
│   │   ├── SqlStatement.java           不可变 SQL 契约（含 sql、参数列表与 StatementMetadata TableRef 集合）
│   │   └── BatchSqlStatement.java      列级元数据批处理契约
│   └── dialect/
│       ├── Dialect.java                内核方言接口
│       ├── DialectStrategy.java        方言解析策略（fixed / cached / perConnection）
│       └── DialectResolver.java        按 DataSource 缓存方言解析器
│
├── statement/factory/                  单表 CRUD 语句工厂
│   ├── SelectStatementFactory.java     按主键 SELECT 语句渲染
│   ├── InsertStatementFactory.java     INSERT 语句渲染
│   ├── UpdateStatementFactory.java     UPDATE 语句渲染（强制非空 WHERE 或显式 allowAll）
│   └── DeleteStatementFactory.java     DELETE 语句渲染（强制非空 WHERE 或显式 allowAll）
│
├── metadata/                           只读实体元数据与操作快照
│   ├── PersistenceAnnotations.java     持久化注解双栈解析器（兼容 jakarta.persistence.* 与 javax.persistence.*）
│   ├── EntityMetadataContext.java      不可变实体表结构元数据
│   ├── EntitySnapshot.java             单次操作实体字段值快照
│   └── MutationContext.java            变更操作上下文
│
└── jdbc/                               JDBC 底层适配与结果集映射
    ├── executor/JdbcExecutor.java      纯 JDBC 执行器（仅依赖 ConnectionProvider）
    ├── dao/                            JoyaMapper 接口及默认实现 JoyaMapperImpl
    ├── resultset/mapper/               RowMapper（BeanRowMapper 原生支持 JavaBean 与 Java 17 Record 规范构造器映射 / SingleColumnRowMapper / MapRowMapper）
    └── support/dialect/                18+ 数据库分页与计数方言实现
```

## 核心设计原则

1. **三段式统一查询流（`DataOps.from(T)`）与职责单一门面**：
   - `DataOps` 提供清晰的三大入口：`db.from(Entity.class)`（唯一查询 DSL 入口）、`db.of(Entity.class)`（实体写与按主键快捷读）、`db.sql(...)`（参数化原生 SQL）。
   - 所有查询遵循 **`from(T) [行过滤: join/where/groupBy/having/orderBy/limit] [.select(列...)] .终结方法(怎么装)`**：
     - 完整实体：`joya.from(User.class).where(..).list()`；
     - 部分列装回实体（单表与联表一致）：`joya.from(Order.class).join(..).select(Order::getId, Order::getAmount).list(Order.class)`；
     - 单列直取：`joya.from(User.class).select(User::getName).list()`；
     - 跨表/聚合按位置构造映射：`joya.from(Order.class).leftJoin(..).select(Order::getId, Order::getAmount, User::getName).page(PageRequest.of(0, 10), OrderUserVO::new)`（内置 `RecordOrderVerifier` 防同类型列顺序颠倒）；
     - 宽 DTO 自动按字段名推列：`joya.from(User.class).where(..).list(UserBrief.class)`；
     - 统计终结方法：`.count()`、`.exists()`、`.max(col)`、`.min(col)`、`.sum(col)`、`.avg(col)`。
   - `JoyaClient`（自动提交）与 `Tx`（显式事务）实现同一个 `DataOps` 接口，业务/仓储方法只需声明接收 `DataOps`，即可在自动提交或任意事务上下文中无缝复用。
2. **极简连接与显式值事务（1 `JoyaClient` = 1 `DataSource`，1 `Tx` = 1 `Connection`）**：
   - 彻底废除 `ThreadLocal` 事务上下文及伪分布式跨库事务包装，多数据源场景直接实例化多个对应的 `JoyaClient`。
   - 事务通过 `joya.tx(tx -> ...)`、`joya.txCall(tx -> ...)` 或 `joya.tx().isolation(...).run(tx -> ...)` 显式传递 `Tx` 值；局部回滚使用 `tx.savepoint(...)` / `tx.savepointCall(...)`。
3. **动态条件零样板（`WhereCriterion`）与默认防误操作**：
   - `WhereCriterion` 的 `eq`、`ne`、`lt`、`lte`、`gt`、`gte`、`between` 在参数值为 `null` 时自动忽略该条件（`IS NULL` 使用显式 `isNull`/`isNotNull`）；`contains`/`startsWith`/`endsWith` 在 `null` 或空白字符串时自动忽略；`in`/`notIn` 在 `null` 或空集合时自动忽略。
   - `EntityUpdate` 与 `EntityDelete` 默认强制要求非空 `WHERE` 条件，全表操作必须显式调用 `.allowAll()`。
   - 外部动态筛选协议通过 `QueryCustomizer` 插件（`PageQueryParams.of(param)`）接入，强制执行标识符正则与实体元数据白名单校验，杜绝 SQL 注入。
4. **拦截器链无旁路与现代 Java 17 映射**：
   - `JoyaTemplate` 是所有 SQL 执行的唯一收口，所有查询、变更、原生 SQL 与批处理 100% 经过 `SqlInterceptor` 链并携带完整 `StatementMetadata`。
5. **Spring Boot 3 旁路（Sidecar）与多数据源原生镜像（`joya-spring-boot-starter`）**：
   - **DataSource 自动镜像（`JoyaClientRegistrar`）**：按容器中的 `DataSource` 1:1 自动注册 `JoyaClient` Bean，自动继承 `@Primary`、`@Qualifier("dsName")` 与自定义 `@Qualifier` 派生注解。
   - **Spring 事务无缝融入（`SpringConnectionProvider` + `SpringTxEngine`）**：通过 `DataSourceUtils` 与 `PlatformTransactionManager` 协同，使 `joya` 操作、`joya.tx(...)`（含 `savepoint`）与 `@Transactional` 及主 ORM 共享同一条事务连接。
   - **`JoyaMapper` 双模支持**：既支持免扫描的 `joya.mapper(XxxMapper.class)` / `tx.mapper(XxxMapper.class)` 随取随用，也支持轻量级按需 `@JoyaMapperScan(basePackages = ..., client = ...)` 多数据源注入。
