# SQL 语句内核重构方案 —— 评审

> 评审对象：[sql-engine-refactoring.md](sql-engine-refactoring.md)（883 行）
> 评审方法：所有结论均以可执行探针在 `joya-core/target/classes` 上实测，非代码走读推测。

## 总评

| 维度 | 评级 | 说明 |
|---|---|---|
| 问题识别准确度 | **良** | 主要矛盾抓对了：双 SQL 生成模型、裸 `List<Object>` 丢失参数语义、`TableMetaContext` 职责混杂 |
| 目标架构方向 | **良** | 单一语句契约 + 有序参数 + 不可变值对象 + 元数据/快照/操作三层拆分，方向符合主流 |
| 面向未来 | **中** | 参数模型选型（复用 Spring 式可变 `SqlParameter`）与"零成本 / GraalVM 友好"的自述目标冲突；批处理与跨库分片未纳入契约设计 |
| 历史包袱清除 | **中** | 删了 Creator 继承体系（真包袱），但引入了新包袱；三处已存在的数据正确性缺陷未被列为缺陷 |
| 最佳实践符合度 | **中** | 缺少最关键的一条不变量（`tables` 元数据完整性），导致分片/租户/审计可静默失效 |

**结论：方向正确，可以按此推进，但落地前必须修订 6 处。** 其中 2 处是**必须修**（否则重构后引入新的静默失效面与性能回退），4 处是**应当修**。

---

## 一、必须修（阻塞级）

### M1 `tables` 元数据完整性未被列为不变量 → 分片/租户/审计静默失效

**这是本方案最大的缺口。** 文档第 8.1 节只讨论"拦截器如何改写参数"，第 9 节只讨论 `withTable` 的字符串安全性，**都没有要求"每个 `SqlStatement` 必须携带完整 `tables`"**。

而 `ShardingInterceptor.supports()` 用 `tables` 非空作为前置条件（`ShardingInterceptor.java:33-35`），于是 `tables` 为空 = 拦截器**静默不生效**。

实测：

```
=== A. tables 齐全时分片生效 ===
  入 : SELECT * FROM t_user WHERE id = ?
  出 : SELECT * FROM t_user_01 WHERE id = ?

=== B. 走 SqlStatement.select(...) 工厂 → tables 为空 ===
  tables = []
  出 : SELECT * FROM t_user WHERE id = ?   ← 未分片，无任何告警

=== C. 批处理（复刻 JoyaTemplate.java:118）===
  tables = []
  出 : INSERT INTO t_user (id, name) VALUES (?, ?)   ← 10 万行全部落错表
```

`tables` 恒为空集的路径：

| 路径 | 证据 |
|---|---|
| `SqlStatement.select/insert/update/delete` 静态工厂 | `SqlStatement.java:84-110`（全部 `Set.of()`） |
| `JoyaTemplate.batchUpdate(String, List<Object[]>)` | `JoyaTemplate.java:118` |
| `JoyaTemplate.execute(String)` | `JoyaTemplate.java:132` |
| `JoyaClient.queryList` / `findList` / `findAll` 等经 select 工厂的全部路径 | `JoyaClient.java:87,138,166,180,187,194,218` |

讽刺的是，文档第 4.3 节把静态工厂当作**改进项**（"减少调用方错误"），但实测这些工厂正是丢失 `tables` 的源头——它们只写死 `Operation`，不校验 SQL 文本一致性，也不产出 `tables`：

```
SqlStatement.select("DELETE FROM user WHERE id = ?") → op = SELECT
tables = []   dbType = other
```

**修订要求**：

1. 把「`SqlStatement` 必须携带完整 `tables`」写成硬不变量，纳入第 11 节测试不变量。
2. `StatementFactory` 是唯一能可靠补齐 `tables` 的时机——必须强制输出。
3. 裸 SQL 逃生舱无法解析 `tables` 时，`SqlStatement` 应携带**显式标记**（如 `tablesResolved = false`），拦截器遇到该标记必须**报错或告警**，而非静默跳过。静默失效比报错危险得多。

### M2 参数模型选型与"零成本"目标冲突 + 批处理设计空缺

文档第 5.1 节建议 `SqlParameter` 为 `record`，含 `index / name / value / source / binding`，其中 `ParameterBinding` 又是一个 `record`（`sqlType / typeName / scale / binder`）。第 3.2 节把"批量参数模板"归到 `BatchSqlStatement` 或 `List<SqlParameterRow>`，**但全文没有展开这个设计**。

推算 10 万行 × 10 列批量插入的对象数：

| 对象 | 数量 | 单个估算 | 合计 |
|---|---|---|---|
| `SqlParameter`（对象头 16B + 4 引用/int ≈ 40B） | 1,000,000 | 40B | ~40 MB |
| `ParameterBinding`（16B + 4 引用 ≈ 48B） | 1,000,000（若每参数独立） | 48B | ~48 MB |
| 行容器 | 100,000 | ~40B | ~4 MB |

**约 210 万个短命对象、~90 MB 瞬时分配**，全部进 young gen 并触发 GC 抖动。

对比现状：`List<Object[]>` 每行 1 个 `Object[]`（`JdbcExecutor.java:396-398` → `:280-298` 绑定），值对象本身已存在，**零额外包装**。

这与文档第 4 节自述的"`record` 无继承、无反射，GraalVM native 友好""零成本"直接冲突。

**修订要求**：

1. 明确批处理走**独立契约**，不复用逐值 `SqlParameter` 包装。建议：

```java
/** 批处理语句：SQL 模板 + 列描述（每列一次）+ 行值（裸数组，零包装）。 */
public record BatchSqlStatement(
        String sql,
        List<ColumnBinding> columns,   // 每列一个，共 N 个，而非 N×行数
        List<Object[]> rows,           // 行值保持裸数组
        StatementMetadata metadata
) {}
```

列描述数量 = 列数（10 个），而非参数总数（100 万个）。类型信息与绑定策略在**列级**声明一次即可——批处理的本质是同构行，逐值携带类型是纯冗余。

2. `ParameterBinding` 应当**共享/驻留**：同一列的所有行共用一个 binding 实例，不要每参数 new。

---

## 二、应当修

### S1 文档遗漏三处已存在的数据正确性缺陷

方案把它们当作"设计不优雅"，但实测它们是**当前就在丢数据的 bug**。不标为缺陷，重构时容易被当作低优先级项跳过，或在迁移中原样带过去。

#### S1-1 实体监听器的修改被静默丢弃（数据正确性）

`JoyaJdbc.saveInternal` 的调用顺序：

```
:852  Map<String, Object> data = context.getData();        ← 置 resolved = true
:866  ((EntityListener) entity).prePersist(entity);        ← 监听器修改实体
:869  new InsertSqlCreator(context, skipNull);             ← 构造器调 initData()
                                                            但 once-only 守卫直接 return
```

`TableMetaContext.initData()` 的守卫（`:122-125`）与 `AbstractSqlCreator` 构造器的刷新意图（`:20-23`）冲突。实测：

```
step1 getData()（saveInternal:852）→ 快照 = {id=1, name=张三, created_by=null}
step2 prePersist（:866）→ 实体 createdBy = audit-system
step3 InsertSqlCreator 构造 → initData() 因 resolved=true 直接返回

生成 SQL = INSERT INTO audit_user (id, name) VALUES(?, ?)
生成参数 = [1, 张三]
>>> 监听器写入的 createdBy=audit-system 未进入 SQL 参数 → 审计字段静默丢失
```

文档第 6.1 节第 4 条**提到了** `resolved` 的问题，6.4 节提出的显式 `snapshot`/`refresh` **能修掉它**——方向对。但必须：

- 在文档中标注这是**已存在的数据正确性缺陷**（审计字段丢失）
- 纳入第 11 节验收标准：「监听器修改实体后，生成的 SQL 参数必须反映修改后的值」

#### S1-2 列顺序确定性假设不成立

文档第 6.3 节：「`columns()` 返回有序、不可变的 `ColumnMetadata` 列表，**列顺序成为 INSERT 和 UPDATE 的稳定基础**」。

实测顺序链条：

```
getColumnNames() → LinkedCaseInsensitiveMap 插入序
                 → 反射扫描时的 Class.getDeclaredFields()
```

`EntityTableMetaDefinition.getColumnNames()`（`:113-120`）依赖 `columnFieldNameRelation` 的插入序，而插入序来自反射扫描。**JLS 不保证 `getDeclaredFields()` 的返回顺序**——HotSpot 实践中按声明序返回，但这是实现细节，不是规范。混淆器、字节码增强、不同 JVM 实现都可能改变它。

把不保证的顺序当作 SQL 参数顺序的"稳定基础"，是把正确性押在实现细节上。

**修订要求**：`ColumnMetadata` 必须有**显式排序键**。建议 `@Column` 声明位置优先，无声明时按列名字典序兜底，并在测试中断言"同一实体两次解析列顺序一致"。

#### S1-3 `EntityTableMetaDefinition.clone()` 是浅拷贝

`LocalTableMetaCache.findByEntityClass` 每次调用 `clone()`（`:103-105`），但 `clone()` 只复制引用（`EntityTableMetaDefinition.java:214-226`）：

```
两个 clone 的 columnFieldRelation 同一实例 = true
```

**既付了分配代价，又没获得真隔离——最坏的组合。** 文档第 6.6 节说「`EntityTableMetaDefinition` 仍然是当前缓存和反射扫描的事实来源」，但没指出这个语义问题。若第一阶段让 `EntityMetadataContext` 包装它，这个半吊子隔离会被继承进新架构。

**修订要求**：`EntityMetadataContext` 必须是**真不可变**（`List.copyOf` / `Map.copyOf` 深度构造一次），然后**取消 clone-on-read**，缓存直接返回同一实例。

### S2 元数据访问的分配热点未被识别

文档目标写了"零成本""GraalVM 友好"，但重构范围未覆盖元数据访问路径。实测每次调用都新建对象：

```
getColumnNames() 两次同一实例      = false
getColumnFiledPairs() 两次同一实例 = false
200000 次 of(Class)+getColumnNames() 耗时 = 113 ms
```

每条实体 SQL 的元数据访问产生：

| 对象 | 来源 |
|---|---|
| `EntityTableMetaDefinition` 外壳 | `LocalTableMetaCache.java:104` clone |
| `LinkedHashSet`（列名） | `EntityTableMetaDefinition.java:113-120` |
| `LinkedHashMap`（列-字段对） | `EntityTableMetaDefinition.java:150-154` |
| `TableMetaContext` | `TableMetaContext.java:70` |
| `LinkedHashMap`（data） | `TableMetaContext.java:29` |

**修订要求**：`EntityMetadataContext` 的 `columns()` / `columnByField()` / `insertableColumns()` 等必须返回**缓存的不可变视图**，构造一次、永久复用。这是 6.3 节"可安全缓存和复用"的必要条件，但文档只写了目标、没写"访问器不得每次新建集合"这条约束。

### S3 `withTable` 的破坏面被低估，且缺 schema

文档第 9 节承认 `sql.replace` 不安全并列了 6 项风险，态度是对的。但实测显示破坏比"清单"更具体，且 `TableRef` 缺 schema 字段（`TableRef.java:14`：只有 `name` + `alias`）：

```
[破坏] 前缀相似表
  入: SELECT * FROM user_role JOIN user ON user.id = user_role.uid
  出: SELECT * FROM user_0_role JOIN user_0 ON user_0.id = user_0_role.uid
[破坏] 列名含表名
  入: SELECT user_name FROM user WHERE user_id = ?
  出: SELECT user_1_name FROM user_1 WHERE user_1_id = ?
[破坏] schema 限定
  入: SELECT * FROM app.user u
  出: SELECT * FROM app.user_3 u        ← 未按 schema 限定判断归属
```

注意 `ShardingInterceptor` 的类注释（`:14-15`）声称 `withTable` "避免 `FROM t_user` 与 `t_user_log` 误替换的错位问题"——**实测正相反**，`String.replace` 恰恰会误替换。这条注释是错的，应一并修正。

**修订要求**：

1. 第一阶段就给 `TableRef` 补 `schema`（文档 9.1 提了"增加 schema、alias、role 信息"，但未落到 `TableRef` 定义）。
2. 第一阶段的"限制表重写只对框架生成的结构化 SQL 生效"必须有**机制保障**——即 M1 的 `tablesResolved` 标记。否则"限制"只是口头约定。
3. 修正 `ShardingInterceptor` 的错误注释。

### S4 参数绑定"复用现有成熟逻辑"指向不明 —— 实为两套实现

文档第 5.2 节：「默认绑定策略使用现有 `SqlDataUtils` 和 `JdbcExecutor` 中成熟的日期、时间、空值处理逻辑」。

**这是两套不同实现，能力不一致**：

| 路径 | 实现 | java.time 处理 |
|---|---|---|
| query / execute | `JdbcExecutor.createPreparedStatement`（`:344-372`） | 有显式 `OffsetDateTime` + Oracle 特例 |
| **批处理** | `SqlDataUtils.setValue`（`:654-748`） | **无**显式分支，全部降级 `setObject` |

实测批处理热路径行为：

```
LocalDateTime + Types.TIMESTAMP   → setObject(LocalDateTime, 93)
LocalDate     + Types.DATE        → setObject(LocalDate, 91)
OffsetDateTime+ Types.TIMESTAMP   → setObject(OffsetDateTime, 93)
java.util.Date+ Types.TIMESTAMP   → setTimestamp(Timestamp)     ← 只有旧 Date 走显式分支
UUID          + TYPE_UNKNOWN      → setObject(UUID)
枚举           + TYPE_UNKNOWN      → setObject(DayOfWeek)
```

`DATE`/`TIME`/`TIMESTAMP` 分支都有 `instanceof java.util.Date` 守卫（`SqlDataUtils.java:693/706/719`），`java.time` 类型全部落到 `else` 分支静默降级。降级本身不算 bug（JDBC 4.2 驱动能映射），但意味着**显式 `sqlType` 对 `java.time` 完全空转，时区语义全交给驱动**，且同一个值走 query 与走 batch 行为可能不同。

`UUID` / `JSON` 无任何映射（全文 grep 无匹配）。

**修订要求**：

1. 明确「统一两套绑定实现」为重构目标，而非含糊的"复用成熟逻辑"。
2. 第 11 节"特殊绑定"测试清单已列 `OffsetDateTime`，应补 `LocalDate` / `LocalDateTime` / `UUID`，并**同时覆盖 query 与 batch 两条路径**（断言两者行为一致）。
3. 慎用 Spring 式 `SqlParameter`（`jdbc/support/SqlParameter.java:38`）作为新模型基类——它是可变类、非 `record`、构造含断言，与文档基调冲突，且与新 `statement/SqlParameter` 同名会造成长期混淆。**新模型应换名**（如 `BoundParameter`）或彻底替换旧类。

### S5 拦截器契约无法表达跨库路由

`SqlInterceptor.intercept(RewriteContext, Chain)`（`SqlInterceptor.java:15-35`）只能改写 `SqlStatement`。`RewriteContext`（`:17-49`）不持有、也无法影响 `DataSource`——连接由 `JdbcExecutor` 构造时注入并固定（`JdbcExecutor.java:72-78`）。

后果：`ShardingInterceptor` 只能做**库内表级分片**（`t_user → t_user_01`），**跨库分片不可达**——SQL 里表名改到另一个库，语句仍在原连接上执行。

这与事务重构方案的约束互为镜像（见 [transaction/04-multi-datasource.md](transaction/04-multi-datasource.md) 的 `mayChangePhysicalDatabase()` 硬约束）。

**修订要求**：文档应明确声明当前契约的能力边界——「本方案只支持库内表级分片；跨库路由需扩展拦截器契约以返回目标数据源键，属后续演进」。不声明边界会让使用者误以为 `ShardingInterceptor` 能做分库。

---

## 三、方案做对的地方（应保持）

| 决策 | 评价 |
|---|---|
| 删除 `SqlCreator` 继承体系 | **正确。** 实测确认它是真包袱：可变 `parameters` 列表 + 手写 `position` + `create()` 里事后排序（`AbstractSqlCreator.java:32-39`），`UpdateSqlByClassCreator.parameters()` 甚至直接抛异常（`:19-21`）——继承体系已破裂 |
| 参数携带 `name` / `source` / 顺序 | **正确。** 现状 `BuilderSupport.statement` 把 `PreparedParameterBinder` 拍平成 `List<Object>`（`BuilderSupport.java:35-37`），名称与来源全丢 |
| `SqlParameterList` 自动分配 index，禁止手写 | **正确。** 直接消灭现状"手写 `index++` + 事后 sort"的脆弱模式 |
| UPDATE 的 SET-before-WHERE 顺序约束 + 强制测试 | **正确且必要。** 现状靠 `UpdateSqlCreator.createInternal` 的书写顺序偶然成立（`:76-88`），无测试保护 |
| `TableMetaContext` 三层拆分 | **正确。** 现状确实混合了元数据、实体快照、`preparedParameterNames`、监听器生命周期（`TableMetaContext.java:26-41`） |
| `MutationContext.allowAll` 默认 `false` | **正确。** 无 WHERE 的 UPDATE/DELETE 默认拒绝是安全默认值 |
| 第 9 节不宣称 `withTable` 能处理任意原生 SQL | **诚实。** 承认能力边界比过度承诺好 |
| 分阶段 + 每阶段验收标准 | **正确。** 阶段划分合理，依赖顺序正确 |

---

## 四、需要澄清的一处误判

### 现有 `SqlStatement` 的不可变性**已经达标**

文档第 4.2 节把"集合字段必须在构造时复制并不可变"列为待修项，并给出 `null SQL → 拒绝或转空串`、`集合 → List.copyOf` 的要求。

实测现状**已经满足**（`SqlStatement.java:32-37`）：

```
外部 List 追加后 statement.args() = [1, 2]  (期望 [1, 2])
args() 不可修改 ← 正确（List.copyOf 生效）
```

`record` 紧凑构造器已做 `List.copyOf` / `Set.copyOf`、`null` SQL 转空串、`null dbType` 转 `DbType.other`。

**修订要求**：4.2 节应改为「保持现有不可变性契约，扩展到新增的 `parameters` / `metadata` 字段」，而非描述成待修缺陷。否则会让实施者误以为需要改动已正确的代码，浪费一个阶段的注意力。

---

## 五、修订清单（可直接执行）

| # | 级别 | 修订内容 | 落到文档哪一节 |
|---|---|---|---|
| M1 | 阻塞 | 增加不变量「`SqlStatement` 必须携带完整 `tables`」；无法解析时用 `tablesResolved=false` 显式标记，拦截器遇标记必须告警/报错 | 新增 4.4 + 第 11 节 |
| M2 | 阻塞 | 展开 `BatchSqlStatement` 设计：列级 `ColumnBinding`（N 个）+ 裸 `Object[]` 行值，禁止逐值包装 | 新增 5.5 |
| S1-1 | 高 | 标注监听器修改丢失为**已存在数据正确性缺陷**，纳入验收标准 | 6.1 + 第 11 节 |
| S1-2 | 高 | `ColumnMetadata` 增加显式排序键（`@Column` 声明序 + 字典序兜底），不依赖 `getDeclaredFields()` | 6.3 |
| S1-3 | 中 | `EntityMetadataContext` 真不可变 + 取消 clone-on-read | 6.6 |
| S2 | 中 | 元数据访问器返回缓存不可变视图，不得每次新建集合 | 6.3 |
| S3 | 中 | `TableRef` 补 `schema`；修正 `ShardingInterceptor` 错误注释 | 第 9 节 |
| S4 | 中 | 明确「统一 query/batch 两套绑定实现」为目标；测试补 `java.time`/`UUID` 并断言两路径一致；新参数模型换名避免与旧 `SqlParameter` 冲突 | 5.2 + 第 11 节 |
| S5 | 中 | 声明能力边界：只支持库内表级分片，跨库路由需扩展契约 | 第 8 节 |
| — | 低 | 4.2 节改为"保持现有不可变性契约并扩展"，删除对现状的误判 | 4.2 |

---

## 附：探针代码

```bash
CP="joya-core/target/classes:$(cd joya-core && mvn -q -o dependency:build-classpath \
    -Dmdep.outputFile=/tmp/sqlprobe/cp.txt >/dev/null && cat /tmp/sqlprobe/cp.txt)"
javac -cp "$CP" -d /tmp/sqlprobe/out /tmp/sqlprobe/*.java
for p in SqlProbe MetaProbe ListenerProbe BindProbe ChainProbe; do
    java -cp "$CP:/tmp/sqlprobe/out" $p
done
```

| 探针 | 验证内容 |
|---|---|
| `SqlProbe` | `withTable` 破坏案例、`appendArg` 尾插语义、不可变性、工厂约束力 |
| `MetaProbe` | `of(Class)` 实例复用性、`clone()` 浅拷贝、分配开销、列顺序确定性 |
| `ListenerProbe` | 监听器修改被 `resolved` 守卫丢弃（审计字段丢失） |
| `BindProbe` | 批处理热路径对 `java.time` / `UUID` / 枚举的实际绑定行为 |
| `ChainProbe` | `tables` 为空导致分片拦截器静默失效 |

这些探针应随重构一并迁入 `joya-core/src/test`，作为 M1 / M2 / S1 的回归防线。
