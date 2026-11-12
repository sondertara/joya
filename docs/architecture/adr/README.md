# 架构决策记录（ADR）

记录已落地的关键取舍：**当时看到了什么、为什么这样选、代价是什么**。只追加，不随实现漂移回改结论。

| 编号 | 决策 | 状态 |
|---|---|---|
| [0001](0001-multi-tenant-save-semantics.md) | 多租户下 `EntityOps.save` 的目标语义：先收敛为「当前租户可见范围内的 upsert」 | Accepted（选项 B 待 PG 矩阵就位后再评估） |
| [0002](0002-pagination-observation.md) | 分页观测双视图：`statement()` = 物理执行语句，`logicalStatement()` 保留逻辑语句 | Accepted |
| [0003](0003-script-scope-and-splitter.md) | `DataOps.script` 定位为轻量逃生舱，切分器升级为词法状态机 | Accepted |
