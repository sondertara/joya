# Changelog

本文件记录 Joya 各版本的架构契约、安全修复与重要行为变更。

## [Unreleased]

### Fixed（缺陷修复与契约打磨）
- **事务启动器与重试配置护栏收敛（`joya-core`）**：
  - `TxOptions` 校验 `retryAttempts >= 0`，`TxStarter#retryOnDeadlock(int)` 校验总尝试次数 `maxAttempts >= 1`（传入 `<= 0` 快速抛 `IllegalArgumentException`），`TxStarter#begin()` 在已配置 `retryOnDeadlock(> 1)` 时立即抛出 `IllegalStateException` 拒绝静默失效。
  - `TxStarter#call` 捕获异常清理时先判 `!tx.isCompleted()` 再调用 `tx.rollback()`，避免手动提前 `commit()` / `rollback()` 或 I7 触发后在主异常上附带多余 `IllegalTxStateException` suppressed；仅在实际触发可重试死锁却因 `!isPhysicalRollbackConfirmed()`（如加入外层 Spring 事务未配 `requiresNew()`）跳过重试时输出 `WARN` 诊断日志。
  - 修正 `Tx#setRollbackOnly()` 与 `TxStarter#retryOnDeadlock(int)` Javadoc 契约说明。

## [1.1.1] - 2026-11-19

### Changed（行为与契约变更）
- **查询逻辑运算符语义重构（Breaking）**：
  - **Breaking**：将 `com.sondertara.joya.core.query.LinkType` 重命名为 `LogicalOperator`（`AND` / `OR`），移除原带前后空格的 `toString()`；`WhereCriterion` 移除 Lombok `@Getter` 依赖并将内部字段 `currentOpt` 规范为 `operator`（同步提供 `getOperator()`）。
- **分页动态筛选嵌套组重构为递归 `FilterGroup`（Breaking）**：
  - **Breaking**：移除 `PageQueryRequest.Nested` 内部类及 `nested` 单槽位字段，改为顶层可递归组合的 `FilterGroup` 与 `PageQueryRequest.filterGroups` 列表（支持任意 `AND` / `OR` 并列与多层递归嵌套，内置 `FilterGroup.MAX_DEPTH = 8` 深度保护与 `copy()` / `clone()` 深拷贝校验）。
  - 迁移指引：`PageQueryRequest.Nested` 请替换为 `FilterGroup` 及 `PageQueryRequest#addOrGroup` / `#addAndGroup` / `#addFilterGroup`；`LinkType` 请替换为 `LogicalOperator`。

### Fixed（缺陷修复）
- **修复 `rootOperator = OR` 下嵌套组外层隔离不足的问题**：
  - `PageQueryParams.of(PageQueryRequest)` 在 `rootOperator == LogicalOperator.OR` 时将顶层 `filters` 与所有 `filterGroups` 一并包裹进同一 `orNested(...)` 括号组，防止多组或组合条件在衔接外层 `.where(...)`（如租户隔离）时因优先级泄漏；并在入口及构建期强制校验 `FilterGroup.MAX_DEPTH`。

## [1.1.0] - 2026-11-19

### Added（新增能力）
- **按需 `@JoyaMapperScan` 扫描注入与主库 `@Qualifier` 对称性（`joya-spring-boot-starter`）**：
  - 新增 `@JoyaMapperScan` 与 `@JoyaMapperScans`（支持 `@Repeatable` 分包配置多数据源）：默认仍保持免扫描零开销，显式标注后由基础设施级 `BeanDefinitionRegistryPostProcessor` 扫描具化了实体类型参数的独立 `JoyaMapper` 子接口（自动忽略未具化的泛型中间父接口，并对用户手工声明的同名 `@Bean` 自动退让）。
  - 支持三级数据源路由优先级：**Mapper 接口 `@Qualifier("dsName")` > `@JoyaMapperScan(client = "dsName")` > 主库 `JoyaClient`**；通过 `FactoryBean.OBJECT_TYPE_ATTRIBUTE` 注册，类型匹配期零提前实例化且原生兼容 Spring Boot 3 AOT。
  - `JoyaNamedClientsAutoConfiguration` 为默认主库 `joyaClient` 同步补齐 `@Qualifier(primaryName)`（默认 `"primary"` 或 `joya.primary.name`，保留 `@Primary`），使 `@Autowired JoyaClient` 与 `@Qualifier("primary") JoyaClient` 完全对称。
- **批量语句交错槽位绑定模型（`BatchSlot`）**：
  - `BatchSqlStatement` 引入 `BatchSlot`（`Row` / `Const` / `RowRef`）与 `SqlRenderer.RenderedBatch`，允许 `SqlRewriter` 在批量 `INSERT` / `UPDATE` / `UPSERT` AST 的任意位置（前置、中间交错或尾部）插入常量参数或重写列槽位，彻底解除旧“行参数 + 尾部追加参数”固定布局假设，并在 `StatementPreparer` 强制执行占位符与槽位数守卫及 `preservesConjunctPredicate` 合取谓词守卫。
- **顶层构建器与流式门面快捷能力补齐**：
  - `JoyaClient.Builder` 新增 `queryTimeout(Duration)`、`batchSize(int)`、`tableNameResolver(TableNameResolver)`、`quoteStyle(QuoteStyle)` 快捷入口；`JoyaSettings` 与 `JoyaSettings.Builder` 新增 `withQueryTimeout(Duration)` / `queryTimeout(Duration)`（亚秒正值向上取整至 1 秒）。
  - `EntityInsert` 新增 `valueIfPresent(IGetter, V)` 与 `valueIfPresent(String, Object)`；`EntityUpdate` 新增 `setIfPresent(String, Object)`（传入 `null` 值时同样立即执行列名与可写性校验）；`DataOps` 新增 `script(String)`。

### Fixed（缺陷修复）
- **`EntityOps.deleteAll` 主键值原样绑定与超长分片前置空值校验**：
  - 修复 `EntityOps.deleteAll` 对非 `Serializable` 标量主键值调用 `.toString()` 导致与单条 `delete(T)` 参数类型不一致的问题，并在超过 1000 条触发分片删除前统一完成 `null` 元素及 `null` 主键前置校验，消除跨分片半程失败的部分提交风险。
- **参数来源分类精确化与超大行数翻页判定修正**：
  - `QueryRenderer` 将 `HAVING` 子句参数来源修正为 `ParameterSource.HAVING`，各数据库方言分页参数统一标记为 `ParameterSource.PAGINATION`。
  - `PageResult.hasNext()` 在已知总数模式下改为 64 位无截断计算 `((long) page + 1L) * (long) size < total`，修复 `totalPages > Integer.MAX_VALUE` 时的截断误判；`PageQueryRequest` 补齐 `toString()`。
- **内核观测作用域与内部重复逻辑收敛**：
  - `ExecutionObserver` 收窄 `try` 作用域避免误捕获监听器自身 `afterExecute` 异常；收敛 `EntityMetadataContext.requirePrimaryKey` / `requireAssignedPrimaryKey`、`Ordered.sort`、`ColumnMetaProvider` 值类型解析及 `EntityDeleter` 冗余包装壳。

### Changed（行为与契约变更）
- **全链路分页模型彻底统一为 0-based `page` + `size`（Breaking）**：
  - **Breaking**：`PageRequest`、`PageQueryRequest` 与 `PageResult` 全链路统一为 **0-based** 页索引（第 1 页 `page = 0`）与 `size`（每页条数）：
    - `PageRequest`：保留唯一工厂 `PageRequest.of(int page, int size)` / `of(int page, int size, boolean needTotal)` 与 `withoutTotal()`，移除 `ofOneBased`、`ofZeroBased`、`getPageNum`、`getPageSize`；
    - `PageQueryRequest`：字段与读写器由 `pageNum = 1` / `pageSize = 20` 改为 `page = 0` / `size = 20`（`normalizedPage()` / `normalizedSize()`）；
    - `PageResult`：字段、静态工厂、`Builder` 及 JSON 序列化属性统一为 `items`、`total`、`page`（0-based）、`size`、`totalPages`、`hasPrev`（`page > 0`）、`hasNext`，移除 `pageNum`、`pageSize`、`getCurrentPage`、`getData`。
- **顶层门面方法与构造入口单一契约收敛（Breaking）**：
  - **Breaking**：`JoyaMapper` 移除隐式全表删库的无参 `deleteAll()`（清空表请显式调用 `truncate()` 或 `ops().delete().allowAll().execute()`），并将接收实体的 `updateById(T)` / `patchById(T)` 重命名为 `update(T)` / `patch(T)`（与 `EntityOps` 对齐，`*ById` 仅用于接收主键值）。
  - **Breaking**：`MutationResult` 将 `getGeneratedKey(Class)` / `getGeneratedKeys(Class)` 重命名为无前缀 `generatedKey(Class)` / `generatedKeys(Class)`，与 record 组件风格一致。
  - **Breaking**：`JoyaClient` 移除公开构造器 `new JoyaClient(DataSource)`，统一收敛于 `JoyaClient.create(DataSource)` 与 `JoyaClient.builder(DataSource)`。

## [1.0.1] - 2026-11-18

### Added（新增能力）
- **客户端身份可观测性与多库 SQL 日志标签（`JoyaClient.Builder.clientName` + `SqlExecutionContext.clientName`）**：
  - `JoyaClient.Builder` 新增 `clientName(String)`，`JoyaClient` 与 `SqlExecutionContext` 新增 `clientName()` 强类型访问器（随 `tx(...)` 显式事务与 `withSettings(...)` 派生视图自动继承）。
  - `joya-spring-boot-starter` 自动为每个客户端注入注册名（如 `primary`、`order`），内置 `joyaSqlLogListener` 输出 `[Joya SQL][<clientName>]` / `[Joya SQL SLOW][<clientName>]` / `[Joya SQL ERROR][<clientName>]`，自定义全局 `SqlExecutionListener` 同样可直接区分目标数据源。

### Fixed（缺陷修复）
- **修复多数据源 `SpringTxEngine` 物理连接/`ThreadLocal` 泄漏与交错重入回调错挂（P0-2）**：
  - `SpringTxSession` 在 `status.isNewTransaction() == true` 时不再受全局 `isActualTransactionActive()` 影响，未提交关闭与显式 `commit()` / `rollback()` 均通过统一的乱序同步补偿执行底层提交/回滚、解绑 `ConnectionHolder` 并清理 `resume()` 恢复的过期同步快照；同源加入型事务在外层提前结束时调用 `commit()` 亦严格抛出 `IllegalTxStateException`。
  - 新增 `SyncOwnerMarker` 与异源活跃事务检测：阻止在 `primary → order → primary(join)` 跨库交错重入时将外层库的 `afterCommit` / `afterCompletion` 错挂到内层异源事务的 `TransactionSynchronization` 列表或误读异源隔离级别。
- **修复自定义带 `@Qualifier` 副库客户端导致主库 `@Primary JoyaClient` 退让串库问题（P0-1）**：
  - `JoyaAutoConfiguration.joyaClient` 改为仅在已存在名为 `joyaClient`、标记 `@Primary` 或无任何限定符的主 `JoyaClient` Bean 时才退让。
- **支持无数据库微服务自动退让（P0-3）**：
  - `JoyaAutoConfiguration` 新增 `OnJoyaDataSourceAvailableCondition`（`REGISTER_BEAN` 阶段）：排除 `DataSourceAutoConfiguration` 且未显式配置自管数据源的服务在启动期优雅跳过。
- **消除 `JoyaDataSourceProperties` 非容器 Bean 裸 `NullPointerException` 与 `hikari` 拼写静默忽略（P0-4）**：
  - `JoyaDataSourceProperties` 解除对 `DataSourceProperties`（`InitializingBean`）的继承，清理不支持的 JNDI/XA IDE 元数据污染，漏配 `.url` 时抛出指明属性路径的 `IllegalStateException`；`hikari` 子属性绑定启用 `NoUnboundElementsBindHandler` 对未知/拼错属性快速失败，补齐 `hasCustomConnectionConfig` 对 `type`/`hikari` 的告警。
- **补齐结果集拉取期与流式迭代期 `SQLException` 的异常翻译（P1-1）与 Spring 6 默认策略对齐（P1-2）**：
  - `SingleRowRecordExtractor`、`ListRecordExtractor`、`ExecutionKernel.queryOne` 与 `ResultSetSpliterator` 统一接入 `SqlExceptionTranslator`，确保游标拉取超时等异常在 Spring 下转换为 `DataAccessException`。
  - `SpringSqlExceptionTranslator` 对齐 Spring 6 `JdbcAccessor` 默认使用零连接开销的 `SQLExceptionSubclassTranslator`（仅存在自定义 `sql-error-codes.xml` 时才启用错误码探测），消除连接池耗尽故障期的二次借连接阻塞。
- **扩展点支持 Spring `@Order` / `Ordered` 排序与自定义无值 `@Qualifier` 隔离（P1-3），命名客户端兼容 Spring 6 AOT（P1-4 & P2-1）**：
  - `JoyaExtensionPointResolver` 支持识别 `@Bean` 方法与类级 `@Order` 及 `org.springframework.core.Ordered`，不再将标注无值自定义 `@Qualifier` 元注解的专用 Bean 误判为全局候选。
  - `JoyaNamedClientsAutoConfiguration` 改用 `factoryBeanName + factoryMethodName` 注册命名 `JoyaClient`，原生通过 Spring 6 AOT 代码生成；`SpringTxSession` 将外层 `TransactionTimedOutException` 统一桥接为 `TxTimeoutException`，`validateConfiguration` 统一采用 `matchesDataSource`。

### Changed（行为与契约变更）
- **移除 `SqlRewriteContext` 与 `SqlExecutionContext` 中的弱类型 `attributes` 映射，收敛为纯强类型 `record`**：
  - **Breaking**：`SqlRewriteContext` 收敛为不可变 `record` 并移除从未有写入入口的 `attributes()` / `attribute(String)` 及对应构造重载，彻底消除 `UpsertPlanner` 预探测门禁与实际改写上下文的潜在分叉风险。
  - **Breaking**：`SqlExecutionContext` 移除 `attributes()`、`ATTR_BATCH` 与 `ATTR_BATCH_ROWS`，将批处理总行数提升为一等强类型字段 `int batchRows()`（单条执行为 `-1`），实现全观测链路零 `HashMap` 分配与零装箱。

## [1.0.0] - 2026-11-15

### Added（新增能力）
- **方言感知的原生 SQL 命名参数绑定（`SqlOps` + `NamedSqlCompiler`）**：
  - `joya.sql(...)` 新增 `bind(name, value)`、`bindMap(Map)` 与 `bindBean(bean)`，支持 `:name` 命名占位符（自动跳过字符串字面量、注释与 PostgreSQL `::type` 类型转换），非空集合与数组自动展开为 `?, ?, ...`。
- **局部不可变派生配置视图（`DataOps.withSettings`）**：
  - `JoyaSettings` 新增 `toBuilder()`，`DataOps` 新增 `withSettings(UnaryOperator<JoyaSettings>)`；`JoyaClient` 派生视图共享底层连接源与元数据缓存，`Tx` 派生视图自动受当前事务剩余 deadline 超时钳制。
- **查询、分页与仓储门面易用性增强**：
  - `PageResult` 新增 `<R> PageResult<R> map(Function)` 转换方法；`SortCondition` 新增基于类型化方法引用 `IGetter` 的 `SortCondition.asc(...)` / `desc(...)` 工厂；`WhereCriterion` 补充 `rangeIfPresent`、`eqIfNotBlank`、`neIfNotBlank`、`startsWithIfNotBlank`、`endsWithIfNotBlank`、`notInChunked`；`JoyaMapper` 补齐 `saveAll`、`patchAll`、`findPageByCondition`、`existsByCondition`、`countByCondition` 等快捷方法。
- **Spring Boot 3 深度集成、容器数据源复用与可观测性（`joya-spring-boot-starter`）**：
  - 默认自动装配惰性 `SpringSqlExceptionTranslator`，统一翻译为 Spring `DataAccessException` 异常体系。
  - `joya.datasources` 支持通过 `data-source-bean` / `transaction-manager-bean` 复用容器既有 Bean，自动为扩展数据源注册带 `@Qualifier("<name>")` 的 `JoyaClient` Bean（与同名 `DataSource` 或用户自定义 Bean 自动避让）。
  - 兼容绑定了 `DataSource` 的 `JpaTransactionManager` 共享事务连接与外层超时 deadline，并在 `TxStarter` 提供 `requiresNew()` 支持在外层托管事务内开启独立新事务。
  - 扩展点自动发现新增 `TableNameResolver` 与 `Executor.Factory`（支持 `@Qualifier` 按数据源路由）；新增 `joya.slow-sql-threshold-ms` 慢 SQL `WARN` 告警与可选 `spring-boot-actuator` 旁路数据源健康检查 `joyaHealthContributor`。

### Security（安全加固）
- **修复多租户场景下 `EntityOps.save` / `saveIgnoreNull` 的跨租户覆写风险**：
  - `UpsertPlanner.save` / `saveAll` 的计划期门禁改为「本实体 + 本表 + `SELECT` / `INSERT` / `UPDATE` 三操作任一存在匹配改写器」才回退（口径与 Performance 段一致）。命中时回退到受行级 `WHERE` 谓词保护的 `existsById` + `insert` / `updateByPrimaryKey` 路径，杜绝通过原生冲突更新覆盖其他租户同主键数据；只处理其他实体或其他操作的 `SqlRewriter` 不再阻断原生 UPSERT。
  - `StatementPreparer` 移除“将内层 `SqlNode.Insert` 改写新增列静默并入 `SqlNode.Upsert.updateColumns`”的隐式逻辑：若改写器未显式重写外层 `SqlNode.Upsert` 却修改了内层 `SqlNode.Insert`（包括直接调用 `ExecutionKernel.update` / `batchExecute` 传入 `Upsert` 语句的场景），现在会立即抛出自解释 `SqlRewriteException`。
- **关闭 `ScriptRunner` 在 `DEBUG` 级别下的结果集明文日志输出**：
  - `ScriptRunner.printResults` 不再打印 `SELECT` 结果集的列名与单元格内容，仅输出 `SQL script result[rows=?, cols=?]` 摘要，与语句指纹脱敏策略保持一致。

### Fixed（缺陷修复）
- `joya-spring-boot-starter` 的扩展点发现不再静默跳过 `FactoryBean` 形式的 Bean：类型探测允许 eager init，且单值扩展点在 Bean 创建中返回惰性代理（E-4）。
- 开启 `joya.sql-log-print-args` 打印明文参数时，按 `joya.sql-log-redact-patterns`（默认覆盖 password/passwd/secret/token/credential）对敏感列脱敏，非法正则启动期失败。
- **时间类型换算统一为“同一时刻”语义（E-2）**：
  - `ConvertUtils` 将 `OffsetDateTime` / `ZonedDateTime` 转 `LocalDateTime` / `LocalDate` / `LocalTime` 时改用 `atZoneSameInstant(ZoneId.systemDefault())`，不再直接调用 `toLocalDateTime()` 丢弃偏移（此前 UTC `10:00` 在 `+08` 环境下会被当成 `10:00` 而非 `18:00`）。
  - `SqlDataUtils` 读取 `OffsetDateTime` 列时优先经 JDBC 4.2 `getObject(index, OffsetDateTime.class)` 直读，保留列内原始偏移（H2 `TIMESTAMP WITH TIME ZONE` 的 `+09:00` 可原样返回）；仅当驱动不支持时才回退 `Timestamp` 路径（该回退只能以 JVM 默认时区表示）。
- **事务 deadline 在批量分片与脚本执行中逐片/逐条重检（E-1）**：
  - `JdbcExecutor.batchExecute` 在每次分片 flush 前重新读取 `ConnectionProvider.queryTimeoutSeconds()`；`ScriptRunner` 新增 `(Connection, DbType, IntSupplier)` 构造器，在每条脚本语句执行前重检剩余秒数（`<= 0` 视为不限时，避免 `setQueryTimeout(0)` 被驱动解释为“无限”）。
- **Spring Boot 自动配置顺序与空事务管理器回退（R-M3）**：
  - `JoyaAutoConfiguration` 通过 `@AutoConfiguration(afterName = ...)` 明确排在 `DataSourceAutoConfiguration`、`TransactionAutoConfiguration`、`HibernateJpaAutoConfiguration` 之后，消除“主库 DataSource / 事务管理器尚未装配完成即解析”的时序窗口。
  - `JoyaTransactionManagerResolver` 在容器内无 `PlatformTransactionManager` 时输出 info 日志（说明 Joya 回退纯 JDBC 引擎、`@Transactional` 连接共享关闭），不再静默。

### Performance（性能）
- `save` / `saveAll` 的原生 UPSERT 放行门禁由「是否注册了任意 `SqlRewriter`」细化为「本实体、本表在 `SELECT` / `INSERT` / `UPDATE` 上是否均无匹配改写器」：只作用于其他实体或其他操作的改写器不再让全库批量 UPSERT 退化为先查后写。
- **参数绑定在已知方言时不再探测驱动（R-M1）**：
  - `ParameterBinder` 新增 `DbType` 重载并把方言透传到 `bindRawValue`，`isOracle` 在方言已知时直接判定；仅未知方言才回退 `DatabaseMetaData` 探测与 `WeakHashMap` 缓存，消除 Oracle 探测路径上的全局 `synchronized` 热锁。批量绑定同样按分片传入 `DbType`。

### Changed（行为与契约变更）
- **`EntityListener` 收敛为实体自实现的零参前置钩子**：
  - 移除全局 `EntityLifecycle`，`EntityListener` 精简为零项目内外部依赖的 `prePersist()` / `preUpdate()` 默认方法，由实体或公共 `BaseEntity` 自实现并在快照前触发（未实现该接口的实体在原生 UPSERT 下零额外探测开销）。
- **分页执行可观测性升级为“物理执行主语句 + 逻辑双视图”（向后兼容构造器签名）**：
  - `SqlExecutionContext.statement()` 的契约统一修正为**实际送入 JDBC 执行器的物理语句**（包含方言 `LIMIT/OFFSET` 或 `OFFSET ... FETCH` 分页包装与 `COUNT(*)` 包装），消除默认 SQL 日志与慢查询监听的分页盲区。
  - `SqlExecutionContext` 新增 `PaginationView` 组件及 `inPage()`、`logicalStatement()`、`offset()`、`limit()` 访问器（保留原三参构造器兼容既有调用点）；需要访问结构化 AST 的监听器请读取 `ctx.logicalStatement().ast()`。
  - 不支持行级分页的方言（如 `Dialect.HIVE` / `PaginationStyle.UNSUPPORTED`）现在与 `countSql` 一致，在进入 `ExecutionObserver` 前立即抛出 `UnsupportedOperationException`（不再触发 `SqlExecutionListener.onError`）。
  - `joya-spring-boot-starter` 内置 `joyaSqlLogListener` 在分页执行时自动附带 `| page=[offset,limit]` 坐标。
- **`DataOps.script` 切换为方言感知词法切分器 `SqlScriptSplitter`**：
  - 保留轻量逃生舱定位（不经过 `SqlRewriter` 与 `SqlExecutionListener`），切分实现由逐行 `contains(";")` 升级为复用 `SqlFormatUtils` 词法规则的整脚本前置切分，支持注释、跨行字符串、PostgreSQL 美元引号、SQL Server 方括号标识符内的分号及行首 `@DELIMITER` 指令；未闭合字面量/块注释与缺失结束分隔符的脚本将在执行任何 SQL 前携带 1 基行号快速失败。
- **Spring Boot Starter 依赖治理与自解释错误增强**：
  - `joya-spring-boot-starter` 保持不绑定特定连接池的轻量旁路定位，改由 `spring-boot-dependencies` BOM 统一管理 Spring 依赖版本，移除手写 `<springframework.version>` 与 `joya-core` 中未使用的 `ojdbc8` 可选依赖。
  - 当自管数据源未显式配置 `.type` 且 classpath 缺少受支持的连接池（如 HikariCP）时，`JoyaDataSourceFactory` 立即抛出指明配置路径与依赖坐标指引的 `IllegalStateException`。
