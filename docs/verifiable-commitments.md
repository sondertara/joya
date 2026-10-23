# Joya 架构与行为承诺对照表（Verifiable Commitments）

本表将 Joya 的每一项核心架构与语义承诺直接映射到对应的自动化测试方法，确保任何承诺均可由 `mvn test` 一键验证。

## 1. 类型安全与延迟绑定边界说明

- **编译期保证**：`Query.select(...)` 返回固定元数的类型化投影阶段（`Select1<A>`..`Select8<A..H>`），在**编译期**强制校验投影列数与构造器引用（如 `Record::new`）的参数个数及对应 Java 类型一致。
- **运行期（SQL 渲染期）保证**：由于 `Query<T>` 不携带联表集合的二阶类型参数，跨表列引用（`IGetter<E, ?>`）的表归属在 **SQL 渲染期**（`RenderContext.resolveAlias`）校验。若引用了未通过 `join`/`leftJoin`/`rightJoin` 注册的实体列，立即抛出自解释异常（指明未关联表名与当前可用表列表），杜绝隐式笛卡尔积。

---

## 2. 承诺 → 验证测试方法对照表

| 领域 | 承诺描述 | 验证测试类与方法 |
|---|---|---|
| **分层边界** | `kernel` 只允许依赖 `kernel` / `naming` / `utils` / `exceptions`；`naming` 与 `model` 零内部依赖；`metadata` 不依赖 `query` / `kernel` | `ArchitectureGuardTest#kernelMayOnlyDependOnKernelNamingUtilsAndExceptions`、`#namingMayNotDependOnAnyOtherProjectPackage`、`#modelMayNotDependOnQueryOrKernel`、`#metadataMayNotDependOnQueryOrKernel` |
| **内核签名** | `JoyaTemplate` 公开方法签名不暴露 `PageResult` / `PageRequest`，仅返回内核 `PageSlice<T>` | `ArchitectureGuardTest#joyaTemplatePublicSignaturesMustNotExposePageResultOrPageRequest` |
| **DML 单一真相源** | 全库仅 `SqlRenderer` 一处负责拼接 `INSERT INTO` / `UPDATE ... SET` / `DELETE FROM` 文本（DQL 尚未收敛，边界见该类 javadoc） | `ArchitectureGuardTest#sqlTextMustOnlyBeBuiltInsideSqlRendererOrDialectLayer` |
| **读写职责分离** | `BatchPlanner` 只做批量写；读/删分别由 `EntityFinder` / `EntityDeleter` 承担且不反向依赖写规划器；`EntityOps` 不自行组装语句 | `ArchitectureGuardTest#batchPlannerMustStayWriteOnly`、`#readAndDeleteCollaboratorsMustNotDependOnBatchPlanner`、`#entityOpsFacadeMustNotAssembleStatements` |
| **SQL 文本快照** | 单表/联表/部分列/分组聚合/DISTINCT 查询与 DML 文本、参数顺序、命名策略转换保持稳定 | `SqlGoldenTest#selectStatements`、`#mutationStatements` |
| **渲染期动态表名** | 动态表名由 `NamingPolicy.tableNameResolver` 在渲染期决定，覆盖 DQL / DML / 批处理 / schema；原生 SQL 不承诺表元数据 | `TableNameResolverTest#tableNameResolver_appliesToDqlRendering`、`#tableNameResolver_appliesToDmlRendering`、`#tableNameResolver_appliesToBatchRendering`、`#rawSqlCarriesNoTableMetadata_byDesign` |
| **表名解析单一入口** | 所有框架生成的 SQL 均经 `EntityMetadataContext.qualifiedTableName(policy)` → `NamingPolicy.resolveTable` | `TableNameResolverTest#tableNameResolver_receivesAlreadyFormattedNames` |
| **命名策略隔离** | `@Column(name = "OrderNo")` 原样保留大小写；不同 `NamingPolicy` 的多个 `JoyaClient` 共享同一实体类时互不污染 | `EntityAndMapperIntegrationTest#joyaSettings_instanceLevelMaxJoinTable_andDualClientNamingPolicyIsolation` |
| **未关联表防护** | 引用未 join 的实体列时，异常消息明确指出未关联表名与当前已注册可用表 | `EntityAndMapperIntegrationTest#lateBinding_whereBeforeJoin_unjoinedEntityGuard_andReusableWhereCriterion` |
| **子句顺序正交** | `where` / `orderBy` 可写在 `join` 之前，渲染期统一解析表别名 | `EntityAndMapperIntegrationTest#lateBinding_whereBeforeJoin_unjoinedEntityGuard_andReusableWhereCriterion` |
| **Record 顺序防呆** | 同类型相邻组件顺序颠倒（如 `(email, name)` 赋给 `(name, email)`）在映射期立即拦截报错 | `QueryDslIntegrationTest#checklist_4_5_recordOrderVerifierAndNamedProjectionGuards` |
| **批量写与回写** | `insertAll` / `insertAllIgnoreNull` / `saveAll` 支持按 `batchSize` 分片冲刷并回写数据库自增主键 | `DialectBatchAndMappingTest#batchOperationsShouldSupportKeyWriteBackSelectiveUpdatePatchIgnoreNullAndChunking` |
| **批量按列更新** | `updateAll(entities, getters...)` 与 `patchAll(entities)` 仅更新指定或非空列，绝不将未声明列覆盖为 `NULL` | `DialectBatchAndMappingTest#batchOperationsShouldSupportKeyWriteBackSelectiveUpdatePatchIgnoreNullAndChunking` |
| **空 WHERE 防护** | `EntityUpdate` / `EntityDelete` 默认拒绝无 `WHERE` 条件的全表修改，除非显式调用 `allowAll()` | `JoyaFluentApiAndTxIntegrationTest#regression_C3_emptyWhereCallbackRejectedOnDeleteAndUpdate` |
| **显式值事务** | 事务作为显式值 `Tx` 传递，支持 `savepoint` / `savepointCall`、连接属性借出前快照还原、`setRollbackOnly` 强回滚与 `close` 幂等 | `TxSemanticsTest#singleTransaction_commitsOnceAndRestoresConnection`、`TxSemanticsTest#I4_isolationRestoredToBorrowedValue`、`TxSemanticsTest#I6_closeIsIdempotent`、`TxSemanticsTest#I7_markedRollbackOnlyMustRefuseCommit` |
| **多数据源隔离** | 事务绑定于各自客户端/数据源，主库事务内调用独立旁路 `JoyaClient` 绝不串用主库连接 | `JoyaFluentApiAndTxIntegrationTest#regression_T1_independentClientDoesNotHijackOtherTransactionConnection` |
