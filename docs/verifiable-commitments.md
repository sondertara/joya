# Joya 架构与行为承诺对照表（Verifiable Commitments）

本表将 Joya 的每一项核心架构与语义承诺直接映射到对应的自动化测试方法，确保任何承诺均可由 `mvn test` 一键验证。

## 1. 类型安全与延迟绑定边界说明

- **编译期保证**：`Query.select(...)` 返回固定元数的类型化投影阶段（`Select1<A>`..`Select8<A..H>`），在**编译期**强制校验投影列数与构造器引用（如 `Record::new`）的参数个数及对应 Java 类型一致。
- **运行期（SQL 渲染期）保证**：由于 `Query<T>` 不携带联表集合的二阶类型参数，跨表列引用（`IGetter<E, ?>`）的表归属在 **SQL 渲染期**（`RenderContext.resolveAlias`）校验。若引用了未通过 `join`/`leftJoin`/`rightJoin` 注册的实体列，立即抛出自解释异常（指明未关联表名与当前可用表列表），杜绝隐式笛卡尔积。同一属性出现在多个表别名下时，named 投影以 `Expr.as(...)` 的别名为唯一标识；未声明别名的重复 label 在渲染期报错。

---

## 2. 承诺 → 验证测试方法对照表

| 领域 | 承诺描述 | 验证测试类与方法 |
|---|---|---|
| **分层边界** | `kernel` 只允许依赖 `kernel` / `naming` / `utils` / `exceptions`；`naming` / `model` / `function` / `exceptions` 零内部依赖；`utils` 不反向依赖 `core`（除 `function`）；`metadata` / `listener` / `statement` / `jdbc` / `query` 单向依赖白名单 | `ArchitectureGuardTest#kernelMayOnlyDependOnKernelNamingUtilsAndExceptions`、`#namingMayNotDependOnAnyOtherProjectPackage`、`#modelMayNotDependOnQueryOrKernel`、`#functionMayNotDependOnAnyOtherProjectPackage`、`#exceptionsMayNotDependOnAnyOtherProjectPackage`、`#utilsMayOnlyDependOnUtilsExceptionsAndFunction`、`#listenerMayOnlyDependOnListenerAndKernelOrdered`、`#statementLayerMayNotDependOnCoreFacadesOrJdbc` |
| **无包级循环** | 除门面簇（`core` / `core.tx` / `core.mapper`）外，包级依赖图不得出现任何循环（Tarjan 强连通分量检测） | `ArchitectureGuardTest#noPackageCyclesExceptTheFacadeCluster` |
| **门面入口与超时一致性** | `JoyaClient` 与 `TxImpl` 共用 `DataOpsSupport`，`Tx` 所有 `DataOps` 入口统一校验所有者线程（不变量 I9）；自定义 `ConnectionProvider` 空超时在自动提交与事务模式下一致回退 `ExecutionOptions.queryTimeoutSeconds` | `TxConnectionSourceTest#txDataOpsEntries_enforceOwnerThreadBeforeTouchingCaches_andBindMapperToOwner`、`TxSemanticsTest#customConnectionProviderAndClientQueryTimeout_areHonoredInTx`、`#customConnectionProviderAndBuilderQueryTimeout_areHonoredInAutocommitToo` |
| **批量与单条同一管线** | 批量与单条语句同样经过 `dbType` 注入 → `SqlRewriter` → `SqlRenderer`；批量 rewriter 只允许尾部追加且同样执行语句形状与重复列校验，单条最终统一校验占位符/参数数量，物理改表禁止 | `KernelIntegrationTest#batchUpdate_multiColumnMultiRow`、`#batchListener_seesBatchFlagRowCountAndDbType`、`#parameterCountMismatch_failsFastBeforeJdbc`、`AstRewriteIntegrationTest#rewriterAddingJoin_refreshesStatementMetadataTables_whileTableRenameFailsFast`、`#batchUpsert_preservesUpsertAstAndSupportsBatchRewriter`、`#saveAndSaveAll_workWithTenantAndInsertRewriters_andRejectBatchColumnReorder`、`#batchRewrite_enforcesShapeAndDistinctColumns_andSupportsFilterWorks`、`#rewriterParameterMismatch_failsFastBeforeJdbc_andImplicitCountPageRewritesOnce` |
| **SQL 构建单一路径** | `Select/Insert/Upsert/Update/Delete` 单条与批量及 `Query`（含 `JOIN ON` 等值键、附加谓词、双列比较与统一 `SqlNode.Like`）全部构建结构化 `SqlNode` 并经唯一公开入口 `SqlRenderer.render(SqlNode, StatementMetadata)` 渲染；21 个包零类级循环，其余 4 个包除显式受控内聚组外无类级循环 | `SqlGoldenTest#selectStatements`、`#mutationStatements`、`AstRewriteIntegrationTest#joinOnAndLikePredicates_produceStructuredAstWithoutRawHoles`、`ArchitectureGuardTest#sqlRendererExposesOnlyAstRenderEntryPoint`、`#allPackagesEnforceClassLevelCycleGuardsExceptCohesiveGroups` |
| **Spring 扩展点自动装配** | `SqlRewriter` / `SqlExecutionListener` / `EntityListener` / `RowMapperFactory` / `SqlExceptionTranslator` 按类型自动发现并按 `@Qualifier` 路由；单值扩展点冲突时启动失败 | `JoyaSpringExtensionPointsTest#extensionBeans_areDiscoveredAndRoutedPerClient`、`#multipleGlobalSingletonExtensionBeans_failFastWithClearMessage`、`#extensionBeanInjectingJoyaClient_resolvesLazilyWithoutCircularDependencyFailure` |
| **执行端口可替换** | `ExecutionKernel` 经 `Executor` 端口执行，`JdbcExecutor` 只在 `kernel.executor` 内被引用；`bind` 事务视图时按新连接来源重建执行器 | `KernelIntegrationTest#executorIsAReplaceableSeam_andRebuiltForBoundTransactionalViews`、`ArchitectureGuardTest#jdbcExecutorIsOnlyReferencedInsideTheExecutorPackage` |
| **分页数据查询可观测** | 显式计数分页与隐式计数分页的计数语句及数据语句均触发 `SqlExecutionListener`（无数据时不执行数据语句；隐式计数分页先对基础语句执行一次 `prepare`，再派生 `countSql` 与 `pageSql`，不重复触发改写器） | `KernelIntegrationTest#explicitCountQueryPage_observesBothCountAndDataStatements`、`#queryPage_observesCountAndDataStatements`、`AstRewriteIntegrationTest#rewriterParameterMismatch_failsFastBeforeJdbc_andImplicitCountPageRewritesOnce` |
| **事务连接来源唯一** | `tx` 包不得直接读取 `DataSource`；`TxEngine` SPI 只产出 `TxSession`，`Tx` 门面统一为 `TxImpl` | `ArchitectureGuardTest#txEnginesMustBorrowConnectionsOnlyThroughConnectionProvider`、`#txEngineSpiMustNotExposeTxFacade`、`TxConnectionSourceTest` |
| **内核签名** | `ExecutionKernel` 公开方法签名不暴露 `PageResult` / `PageRequest`，仅返回内核 `PageSlice<T>` | `ArchitectureGuardTest#executionKernelPublicSignaturesMustNotExposePageResultOrPageRequest` |
| **DQL / DML 渲染单一真相源** | 全库仅 `SqlRenderer.render(SqlNode, StatementMetadata)` 一处负责把 AST 拼接为标准 DQL / DML 文本（方言分页语法归属 `kernel.dialect`） | `ArchitectureGuardTest#sqlTextMustOnlyBeBuiltInsideSqlRendererOrDialectLayer`、`#sqlRendererExposesOnlyAstRenderEntryPoint` |
| **读写职责分离** | `InsertPlan` / `UpdatePlan` 只做写规划；读/删分别由 `EntityFinder` / `EntityDeleter` 承担；`EntityOps` 不自行组装语句 | `SqlGoldenTest#mutationStatements`、`ArchitectureGuardTest#sqlTextMustOnlyBeBuiltInsideSqlRendererOrDialectLayer` |
| **SQL 文本快照** | 单表/联表/部分列/分组聚合/DISTINCT 查询与 DML 文本、参数顺序、命名策略转换保持稳定 | `SqlGoldenTest#selectStatements`、`#mutationStatements` |
| **渲染期动态表名** | 动态表名由 `NamingPolicy.tableNameResolver` 在渲染期决定，覆盖 DQL / DML / 批处理 / schema；原生 SQL 不承诺表元数据 | `TableNameResolverTest#tableNameResolver_appliesToDqlRendering`、`#tableNameResolver_appliesToDmlRendering`、`#tableNameResolver_appliesToBatchRendering`、`#rawSqlCarriesNoTableMetadata_byDesign` |
| **表名解析单一入口** | 所有框架生成的 SQL 均经 `EntityMetadataContext.qualifiedTableName(policy)` → `NamingPolicy.resolveTable` | `TableNameResolverTest#tableNameResolver_receivesAlreadyFormattedNames` |
| **命名策略隔离** | `@Column(name = "OrderNo")` 原样保留大小写；不同 `NamingPolicy` 的多个 `JoyaClient` 共享同一实体类时互不污染 | `EntityAndMapperIntegrationTest#joyaSettings_instanceLevelMaxJoinTable_andDualClientNamingPolicyIsolation` |
| **未关联表防护** | 引用未 join 的实体列时，异常消息明确指出未关联表名与当前已注册可用表 | `EntityAndMapperIntegrationTest#lateBinding_whereBeforeJoin_unjoinedEntityGuard_andReusableWhereCriterion` |
| **子句顺序正交** | `where` / `orderBy` 可写在 `join` 之前，渲染期统一解析表别名 | `EntityAndMapperIntegrationTest#lateBinding_whereBeforeJoin_unjoinedEntityGuard_andReusableWhereCriterion` |
| **Record 顺序防呆** | 同类型相邻组件顺序颠倒（如 `(email, name)` 赋给 `(name, email)`）在映射期立即拦截报错 | `QueryDslIntegrationTest#checklist_4_5_recordOrderVerifierAndNamedProjectionGuards` |
| **列别名来源与注入防护** | 列别名可由 DTO getter 声明（`as(VO::getX)`）并校验返回类型兼容性，非法标识符别名一律拒绝 | `AliasAndColumnCompareTest#asWithDtoGetter_emitsExactlyThePropertyName`、`#asWithString_rejectsNonIdentifierAndDot` |
| **同名列与同表多次关联** | 支持通过显式表别名（`join(..., "buyer")`）进行自连接与同表多次关联；同一属性来自不同表别名时需以 `as(...)` 区分后做 named 投影 | `AliasAndColumnCompareTest#sameColumnFromTwoAliases_needsAliasForNamedMapping`、`#sameTableTwice_withExplicitAliases_mapsByNameAndComparesColumns`、`#selfJoinSameColumn_requiresExplicitAlias` |
| **列间比较** | `eq/ne/gt/gte/lt/lte` 支持两侧均为列/表达式引用且不产生 JDBC 参数 | `AliasAndColumnCompareTest#columnComparison_allSixOperatorsAndEmitsNoJdbcParameter` |
| **批量写与回写** | `insertAll` / `insertAllIgnoreNull` / `saveAll` 支持按 `batchSize` 分片冲刷并回写数据库自增主键 | `DialectBatchAndMappingTest#batchOperationsShouldSupportKeyWriteBackSelectiveUpdatePatchIgnoreNullAndChunking` |
| **批量按列更新** | `updateAll(entities, getters...)` 与 `patchAll(entities)` 仅更新指定或非空列，绝不将未声明列覆盖为 `NULL` | `DialectBatchAndMappingTest#batchOperationsShouldSupportKeyWriteBackSelectiveUpdatePatchIgnoreNullAndChunking` |
| **空 WHERE 防护** | `EntityUpdate` / `EntityDelete` 默认拒绝无 `WHERE` 条件的全表修改，除非显式调用 `allowAll()` | `JoyaFluentApiAndTxIntegrationTest#regression_C3_emptyWhereCallbackRejectedOnDeleteAndUpdate` |
| **显式值事务** | 事务作为显式值 `Tx` 传递，支持 `savepoint` / `savepointCall`、连接属性借出前快照还原、`setRollbackOnly` 强回滚与 `close` 幂等 | `TxSemanticsTest#singleTransaction_commitsOnceAndRestoresConnection`、`TxSemanticsTest#I4_isolationRestoredToBorrowedValue`、`TxSemanticsTest#I6_closeIsIdempotent`、`TxSemanticsTest#I7_markedRollbackOnlyMustRefuseCommit` |
| **多数据源隔离** | 事务绑定于各自客户端/数据源，主库事务内调用独立旁路 `JoyaClient` 绝不串用主库连接 | `JoyaFluentApiAndTxIntegrationTest#regression_T1_independentClientDoesNotHijackOtherTransactionConnection` |
