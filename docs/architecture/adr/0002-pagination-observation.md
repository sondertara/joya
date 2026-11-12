# ADR-0002：分页观测的双视图语义

- 状态：Accepted（2026-11）
- 关联：评审 R-M4；实现见 `SqlExecutionContext#statement` / `#logicalStatement` / `#pagination`

## 背景

`COUNT(*)` 包装与 `LIMIT / OFFSET` 由 `Dialect` 在 `SqlRewriter` 之后生成。此前数据语句在 `observe` 时传入的是**未分页**文本，
而同一方法内的计数语句却传入包装后的文本 —— `statement()` 语义双标，监听器、starter 默认日志与第三方审计都看不到真实执行的分页语句。

## 决策

- `ctx.statement()` 统一为**实际送入 JDBC 的语句**（含 count / 分页包装）。
- 新增 `ctx.logicalStatement()`：改写完成、包装之前的逻辑语句（保留 `ast()`）。
- 新增 `ctx.pagination()`：非空时提供 `offset` / `limit`，非分页场景为 `null`；保留三参数构造器兼容。
- `pageSql` 在 `observe` 之前构造，确保双视图在同一时刻成立。

## 理由

- 只替换主语句会丢失逻辑 SQL，审计与跨方言对比失效；`page_arg_N` 参数也无法区分 offset 与 limit 的语义。
- 只保留旧语义则默认路径继续「说谎」，R-M4 的盲区只会对主动迁移者消失。
- 分页样式全部参数化（`LIMIT ?` / `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`），物理 SQL 文本基数与逻辑同阶，不存在「内联常量导致基数爆炸」的问题。

## 代价

- `statement()` 语义变更是**行为变更**（非 API 破坏）：既有断言需同步；需要 AST 的监听器改用 `logicalStatement()`。
- `UNSUPPORTED` 方言的「不支持分页」错误提前到 `observe` 之前抛出，不再触发 `onError`（按配置错误 Fail-Fast 处理）。
- `script` / `withConnection` 仍不经过观测管线（逃生舱语义，见 ADR-0003）。
