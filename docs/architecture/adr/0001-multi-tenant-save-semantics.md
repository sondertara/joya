# ADR-0001：多租户下 `EntityOps.save` 的目标语义

- 状态：Accepted（2026-11）
- 关联：评审 R-H1；实现见 `UpsertPlanner` 门禁与 `StatementPreparer` 的 Upsert 改写规则

## 背景

`save` 在方言支持时走原生 UPSERT（无冲突 `WHERE` 谓词），而多租户隔离由 `SqlRewriter` 实现。
当租户 t1 对属于 t2 的主键执行 `save` 时，数据库按**全局主键**命中冲突分支，直接覆盖 t2 的整行并把 `tenant_id` 改写为 t1。
更糟的是 H2 的 `MERGE` 渲染**忽略** `updateColumns`，所有插入列在冲突时都会被覆盖；因此「只禁止把改写器新增列并入更新列」并不能消除劫持。

## 决策

1. **`save` 的语义定为「当前租户可见范围内的 upsert」**：跨可见范围的主键视为不可见但已被占用，最终以唯一键冲突暴露，而不是静默改数据。
2. **只要存在可能生效的改写器（`SELECT` / `INSERT` / `UPDATE` 任一命中本实体、本表），`save` / `saveAll` 统一回退到受行级 `WHERE` 保护的先查后写**，与 `saveAll` 既有行为对齐。
3. **`StatementPreparer` 不再把改内层 `Insert` 新增的列并入 `updateColumns`**；改写外层 `Upsert` 仍允许。
4. 原生 UPSERT 的结构化冲突谓词（PostgreSQL `DO UPDATE ... WHERE`）**延后**，届时作为上述回退路径之上的方言快速路径（选项 A ⊂ 选项 B）。

## 理由

- 承重点在 **planner 层回退**，不是渲染或列集合细节：只要还走原生 UPSERT，无冲突谓词就必然覆盖整行。
- 选项 B 需要给 `public sealed` 的 `SqlNode.Upsert` 增加第 5 个组件、新增「改写器提供冲突谓词」的契约，并且 MySQL/H2 的降级必须发生在**计划期**（渲染器无法更换执行计划）；而当前测试矩阵只有 H2、无 Testcontainers，PG 分支将零覆盖。
- 失败模式符合 QS-1：跨租户写要么 0 行、要么唯一键冲突；不会出现「静默覆盖 + 归属被改」。

## 代价

- 存在改写器的客户端，单条 `save` 由 1 条 SQL 变为 2 条（SELECT + INSERT/UPDATE），批量 `saveAll` 回退需放在 `joya.tx(...)` 内才具备整批原子性。
- 门禁是保守判定：改写器若只对「其他实体 / 其他操作」生效，原生路径保留；若 `supports()` 依赖 batch / rootAlias / attributes，门禁可能偏乐观（已在 Javadoc 中写明契约）。
