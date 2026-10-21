# 事务模块重构方案

> 面向 Joya 全新 ORM 的事务内核重设计。本目录是**落地方案**，不是设想稿：所有对现状的指控均已用可执行探针复现，所有目标设计均给出不变量、接口签名与验收标准。

## 文档结构

| 文档 | 内容 |
|---|---|
| [01 现状评估](01-assessment.md) | 实测缺陷清单（P0/P1/P2）+ 复现方法 + 根因分析 |
| [02 目标架构](02-design.md) | 九条不变量、分层、状态机、传播语义收敛 |
| [03 API 契约](03-api.md) | 完整接口签名、使用形态、Spring 互操作 |
| [04 多数据源](04-multi-datasource.md) | 注册表、路由、跨库工作单元、XA 插件 |
| [05 落地路线](05-migration.md) | 分阶段实施、测试矩阵、验收标准、清理清单 |
| [**06 最终落地方案**](06-final-plan.md) | **执行版**：边界（明确不做）、最终阶段计划、优先级矩阵、相对 02/05 的修正记录 |
| [**08 用例驱动的 API 设计**](08-api-by-use-case.md) | **用户视角**：五层 API、只需 3 个概念、内部类型反推。与 03 互补，用户可见 API 以本文为准 |

## 一句话结论

现有事务模块**复刻了 Spring 2003 年 `DataSourceTransactionManager` 的形状，却丢掉了让那套设计正确的不变量**，导致 3 个数据正确性级别的缺陷（嵌套事务摧毁外层事务、单例事务栈跨线程串扰、传播行为在 `autoCommit=false` 池下全线误判）。

**结论：不值得改，值得重写。** 重写方向不是"把 Spring 抄对"，而是换范式——**事务是你持有的值，不是你祈祷已绑定的环境状态**。

## 核心决策速查

| 决策 | 现状 | 目标 | 理由 |
|---|---|---|---|
| 事务载体 | `ThreadLocal` 环境态 + 实例字段 Deque | `Tx` 显式句柄，唯一 SQL 执行入口 | 环境态在 Loom / 异步下必然失效；显式句柄无自调用问题 |
| 事务边界表达 | 切换 `autoCommit` 并反推 | 显式 `TxState` 状态机 | 反推在 `autoCommit=false` 池下全线误判 |
| 物理提交权 | 任意嵌套层均可 `setAutoCommit(true)` | 仅 `isNew == true` 的最外层 | 消灭"内层结束隐式提交外层"P0 缺陷 |
| 传播行为 | 7 值运行时枚举 | 运行时 3 值 + 4 个断言 | `MANDATORY`/`NEVER`/`SUPPORTS`/`NOT_SUPPORTED` 是前置条件，不是传播 |
| 连接状态恢复 | 单字段 `originalIsolation` | 最外层捕获一次 `RestorePoint` | 消灭嵌套覆盖导致的连接池污染 |
| 引用计数 | `retain`/`release` 不对称 | 删除，`Session` 独占所有权 | 无计数即无不对称 |
| 参与者失败 | 静默 | 全局 rollback-only + `UnexpectedRollbackException` | 静默数据丢失 → 响亮报错 |
| 异常合成 | `finally` 覆盖业务异常 | 业务异常胜出 + `addSuppressed` | 保留根因 |
| 多数据源 | 不支持（starter 主动拒绝） | `DataSourceRegistry` + 事务开始时钉死路由 | 规避 Spring 路由数据源两个经典 bug |
| 声明式事务 | 死代码，完全未生效 | 传递环境句柄 + `REQUIRE_TX` 策略 | 杜绝静默非事务写入 |

## 阅读顺序建议

- 只想知道"为什么必须重写" → 读 [01 现状评估](01-assessment.md)
- 只想知道"新 API 长什么样" → 读 [03 API 契约](03-api.md)
- 准备动手实施 → 读 [02](02-design.md) → [04](04-multi-datasource.md) → [05](05-migration.md)
