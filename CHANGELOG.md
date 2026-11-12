# Changelog

本文件记录 Joya 各版本的架构契约、安全修复与重要行为变更。

## [Unreleased]

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
