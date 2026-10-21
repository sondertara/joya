# SQL 语句内核重构方案

## 1. 背景与目标

当前项目同时存在两套 SQL 生成与执行模型：

```text
旧模型：实体元数据 + SqlCreator → SQL + PreparedParameterBinder → JoyaJdbc
新模型：Builder → SqlStatement → JoyaTemplate → Interceptor → Executor
```

两套模型都能生成 SQL，但契约不一致，导致：

- `SqlStatement` 只携带 `List<Object>`，参数名称、来源、JDBC 类型和绑定策略全部丢失；
- `SqlCreator` 通过可变状态和 `TableMetaContext.data` 传递本次操作数据；
- `TableMetaContext` 同时承担元数据容器、实体数据快照、监听器生命周期和临时参数上下文；
- Builder、JoyaClient、JoyaJdbc、Mapper 分别依赖不同的 SQL 生成路径；
- SQL 改写后缺少可靠的参数追加、参数重排和绑定能力。

本次重构的目标不是简单替换类名，而是建立唯一、可靠的 SQL 语句契约：

```text
EntityMetadata + OperationContext
        ↓
StatementFactory
        ↓
SqlStatement(sql + ordered SqlParameter + metadata)
        ↓
SqlInterceptor
        ↓
Executor + ParameterBinder
```

目标包括：

1. 删除 `SqlCreator` 目录及其继承体系；
2. 吸收 Creator 中成熟的列选择、生成列排除和参数顺序规则；
3. 用有序参数对象替代裸 `List<Object>`；
4. 让 SQL、参数和语句元数据组成不可变值对象；
5. 将 `TableMetaContext` 收敛为纯元数据与实体快照上下文；
6. 让实体 CRUD、新 Builder、原生查询最终都进入同一条执行链路；
7. 保留迁移期间的兼容能力，但不继续扩大旧模型的使用范围。

---

## 2. 最终架构

### 2.1 分层结构

```text
com.sondertara.joya.core
├── metadata/
│   ├── EntityMetadata.java
│   ├── EntityMetadataResolver.java
│   ├── TableMetadata.java
│   └── ColumnMetadata.java
├── statement/
│   ├── SqlStatement.java
│   ├── SqlParameter.java
│   ├── SqlParameterList.java
│   ├── StatementMetadata.java
│   ├── ParameterSource.java
│   └── ParameterBinder.java
├── statement/factory/
│   ├── InsertStatementFactory.java
│   ├── UpdateStatementFactory.java
│   ├── DeleteStatementFactory.java
│   └── SelectStatementFactory.java
├── builder/
│   ├── InsertBuilder.java
│   ├── UpdateBuilder.java
│   └── DeleteBuilder.java
└── jdbc/support/
    └── TableMetaContext.java
```

包名可根据当前项目实际结构调整，但职责应保持稳定：

```text
元数据层       只描述实体和表结构
Builder        只收集业务意图
StatementFactory 负责实体 CRUD SQL 渲染
SqlStatement   负责跨层传输完整语句
Interceptor    只做语句改写
Executor       只负责执行
ParameterBinder 只负责 JDBC 参数绑定
```

### 2.2 统一调用链

```text
JoyaClient / JoyaOperations
        ↓
InsertBuilder / UpdateBuilder / DeleteBuilder
        ↓
StatementFactory
        ↓
SqlStatement
        ↓
JoyaTemplate.rewrite()
        ↓
SqlInterceptor 链
        ↓
Executor
        ↓
JdbcExecutor
        ↓
ParameterBinder
        ↓
PreparedStatement
```

`JoyaJdbc` 和 Mapper 的旧入口可以在迁移期间存在，但生成的实体 CRUD 语句必须逐步切换到 `StatementFactory`，不能继续新增对 `SqlCreator` 的直接引用。

---

## 3. 删除 Creator 的范围

### 3.1 最终删除

以下类型在迁移完成后删除：

```text
SqlCreator
AbstractSqlCreator
GenericSqlCreator
InsertSqlCreator
UpdateSqlCreator
DeleteSqlCreator
SelectSqlCreator
UpdateBatchByIdSqlCreator
UpdateSqlByClassCreator
```

删除前必须确认全工程没有以下引用：

- `JoyaClient`；
- `JoyaJdbc`；
- `BuilderSupport` 和三个 Builder 实现；
- Mapper、DAO、`JPAQueryParser`；
- 测试和示例文档。

### 3.2 需要吸收的能力

| Creator 能力 | 新归属 |
|---|---|
| 实体字段到列名映射 | `EntityMetadata` / `ColumnMetadata` |
| 排除自增、生成列 | `InsertStatementFactory` |
| `ignoreNull` | `InsertStatementFactory` / `UpdateStatementFactory` |
| 显式列集合 | 操作上下文或 Builder 配置 |
| SET 参数先于 WHERE 参数 | `SqlParameterList` 的渲染顺序约束 |
| WHERE 参数追加 | `WhereRenderer` + `SqlParameterList` |
| JDBC 类型绑定 | `SqlParameter` + `ParameterBinder` |
| 批量参数模板 | `BatchSqlStatement` 或 `List<SqlParameterRow>` |

不要把 Creator 的实现原样复制到 Builder。Builder 不应该负责拼接实体 SQL，也不应该感知 JDBC Binder。

---

## 4. 新的 `SqlStatement` 设计

### 4.1 核心模型

当前模型：

```java
record SqlStatement(
    String sql,
    List<Object> args,
    Set<TableRef> tables,
    Operation op,
    DbType dbType
)
```

目标模型：

```java
public record SqlStatement(
        String sql,
        List<SqlParameter> parameters,
        StatementMetadata metadata
) {
}
```

```java
public record StatementMetadata(
        Operation operation,
        DbType dbType,
        Set<TableRef> tables,
        Class<?> entityType
) {
}
```

`StatementMetadata` 用于承载语句级信息，避免在 `SqlStatement` 顶层继续堆积可选字段。

### 4.2 不可变性要求

`SqlStatement`、`SqlParameter`、`StatementMetadata` 的集合字段必须在构造时复制并不可变：

```text
null SQL       → 拒绝或统一转为空字符串，二者选其一并固定
null parameters → 空列表
null metadata   → 默认元数据或明确拒绝
集合           → List.copyOf / Set.copyOf
```

所有改写操作返回新对象：

```text
withSql
withParameters
withMetadata
appendParameter
replaceParameter
withTable
```

禁止拦截器直接修改原对象内部集合。

### 4.3 工厂方法

应提供表达操作语义的工厂方法，减少调用方错误：

```text
SqlStatement.select(...)
SqlStatement.insert(...)
SqlStatement.update(...)
SqlStatement.delete(...)
```

工厂方法负责写入 `Operation`，调用方不再手动传入互相矛盾的 SQL 和操作类型。

---

## 5. 参数模型与顺序设计

### 5.1 `SqlParameter`

建议模型：

```java
public record SqlParameter(
        int index,
        String name,
        Object value,
        ParameterSource source,
        ParameterBinding binding
) {
}
```

其中：

```java
public enum ParameterSource {
    INSERT,
    SET,
    WHERE,
    JOIN,
    TENANT,
    AUDIT,
    PAGINATION,
    RAW
}
```

```java
public record ParameterBinding(
        Integer sqlType,
        String typeName,
        Integer scale,
        ParameterBinder binder
) {
}
```

第一阶段如果不需要完整 JDBC 类型信息，可以先将 `ParameterBinding` 简化为可空的 `JdbcType`，但 `name`、`source` 和顺序不能丢弃。

### 5.2 `ParameterBinder`

参数描述和参数绑定行为分离：

```java
@FunctionalInterface
public interface ParameterBinder {
    void bind(
            PreparedStatement statement,
            SqlParameter parameter
    ) throws SQLException;
}
```

默认绑定策略使用现有 `SqlDataUtils` 和 `JdbcExecutor` 中成熟的日期、时间、空值处理逻辑；特殊类型再通过独立 Binder 扩展。

不要原样保留可变的 `PreparedParameterBinder`。它的精华是参数的顺序、名称和值，而不是可变 DTO 本身。

### 5.3 自动维护顺序

新增内部收集器：

```java
final class SqlParameterList {
    SqlParameterRef add(
            String name,
            Object value,
            ParameterSource source
    );

    List<SqlParameter> toList();
}
```

调用方只声明参数，不能手写 `index`：

```text
先 add SET 参数
再 add WHERE 参数
最后 add 拦截器追加参数
```

收集器自动分配：

```text
index = parameters.size() + 1
```

最终不变量：

```text
SqlParameter.index 从 1 开始连续递增
SQL 中第 N 个 ? 对应 parameters[N - 1]
```

### 5.4 UPDATE 参数顺序

必须固定为：

```text
SET 参数 → WHERE 参数 → 拦截器追加参数
```

例如：

```sql
UPDATE user
   SET name = ?, age = ?
 WHERE id = ? AND tenant_id = ?
```

对应：

```text
1. name     / SET
2. age      / SET
3. id       / WHERE
4. tenantId / TENANT 或 WHERE
```

这条规则必须有单元测试，而不能依赖 `Map` 的偶然迭代顺序。

---

## 6. `TableMetaContext` 优化方案

### 6.1 当前问题

当前 `TableMetaContext` 混合了以下职责：

```text
EntityTableMetaDefinition
entity
entityClass
data
nullToDefaults
propertyNullable
resolved
preparedParameterNames
EntityListener 生命周期
```

主要风险：

1. `getData()` 是延迟初始化且可变的；
2. Builder 可能把本次 values 写入 `context.data`；
3. 缓存的元数据和本次操作数据存在污染风险；
4. `resolved` 只允许一次初始化，实体监听器修改实体后需要重新初始化；
5. `preparedParameterNames` 不属于表元数据；
6. `TableMetaContext` 既表示 `Class` 查询，又表示实体实例操作，语义不清晰；
7. 字段名、列名和大小写不敏感规则分散在多个对象中。

### 6.2 最终拆分

将当前类拆成三个层次：

```text
EntityMetadataContext
    只持有 EntityTableMetaDefinition 和规范化的表/列元数据

EntitySnapshot
    从实体实例读取出的字段值快照

MutationContext
    本次 INSERT / UPDATE / DELETE 的 values、条件和选项
```

推荐职责：

| 类型 | 内容 | 生命周期 |
|---|---|---|
| `EntityMetadataContext` | 表名、schema、列、主键、生成列、字段映射 | 缓存级、只读 |
| `EntitySnapshot` | 实体实例对应的列值 | 单次操作 |
| `MutationContext` | values、where、ignoreNull、allowAll | 单次 Builder |

### 6.3 元数据上下文设计

`EntityMetadataContext` 应满足：

```text
由 entityType 唯一确定
不持有业务 values
不持有 PreparedParameter
不触发 SQL 生成
不被 Builder 修改
可安全缓存和复用
```

建议提供：

```text
entityType()
tableName()
schemaName()
qualifiedTableName()
primaryKey()
columns()
columnByField(fieldName)
fieldByColumn(columnName)
generatedColumns()
insertableColumns()
updatableColumns()
```

其中 `columns()` 返回有序、不可变的 `ColumnMetadata` 列表，列顺序成为 INSERT 和 UPDATE 的稳定基础。

### 6.4 实体快照设计

```java
public final class EntitySnapshot {
    private final EntityMetadataContext metadata;
    private final Object entity;
    private final Map<String, Object> values;
}
```

读取实体字段时：

```text
1. 按 metadata.columns() 顺序读取
2. 应用 null 默认值规则
3. 生成不可变 Map
4. EntityListener 修改实体后显式重新 snapshot
```

不要再通过 `resolved` 隐式判断快照是否有效。建议使用显式方法：

```text
EntitySnapshot snapshot(entity)
EntitySnapshot refresh(entity)
```

### 6.5 操作上下文设计

```java
public record MutationContext(
        EntityMetadataContext metadata,
        Map<String, Object> values,
        WhereCriterion where,
        Set<String> declaredColumns,
        boolean ignoreNull,
        boolean allowAll
) {
}
```

要求：

- `values` 不写入元数据上下文；
- values 按字段名或列名统一解析，不能两套规则混用；
- 构造时复制集合；
- `allowAll` 默认 `false`；
- `UPDATE` 和 `DELETE` 没有条件时必须显式允许全表操作；
- `declaredColumns` 只影响本次操作。

### 6.6 与现有 `EntityTableMetaDefinition` 的关系

不建议立即删除 `EntityTableMetaDefinition`。它仍然是当前缓存和反射扫描的事实来源。

迁移策略：

```text
CacheHolder.tableMetaCache()
        ↓
EntityMetadataResolver
        ↓
EntityMetadataContext
        ↓
StatementFactory
```

第一阶段可以让 `EntityMetadataContext` 包装现有 `EntityTableMetaDefinition`；第二阶段再逐步把：

- 列元数据；
- 主键/生成列；
- schema/table 名；
- field-column 双向关系；

从可变 Definition 转成不可变元数据对象。

---

## 7. StatementFactory 设计

### 7.1 INSERT

`InsertStatementFactory` 负责：

```text
1. 获取 EntityMetadataContext
2. 确定显式列或默认 insertableColumns
3. 排除自增主键和其他 generatedColumns
4. 按 metadata.columns() 的稳定顺序排列
5. 根据 ignoreNull 过滤值
6. 通过 SqlParameterList 添加参数
7. 返回 INSERT SqlStatement
```

规则：

```text
SQL 列顺序 = 参数顺序 = metadata 中的最终列顺序
```

### 7.2 UPDATE

`UpdateStatementFactory` 负责：

```text
1. 确定最终 SET 列
2. 排除不可更新列
3. 应用 ignoreNull
4. 先渲染 SET 和 SET 参数
5. 再渲染 WHERE 和 WHERE 参数
6. 返回 UPDATE SqlStatement
```

### 7.3 DELETE

`DeleteStatementFactory` 负责：

```text
1. 验证 where 或 allowAll
2. 渲染表名
3. 渲染 WHERE
4. 按条件出现顺序添加参数
5. 返回 DELETE SqlStatement
```

`allowAll` 是 Builder 的安全语义，Factory 只接收已经验证过的 `MutationContext`。

### 7.4 SELECT

查询迁移放在写操作之后。`SelectStatementFactory` 负责吸收 `SelectSqlCreator` 的：

```text
实体列映射
主键查询
指定列查询
findAllById
表别名和列别名
```

分页、JOIN、复杂条件应优先复用现有 query parser 和 dialect，不要将所有查询能力重新塞入实体 CRUD Factory。

---

## 8. Interceptor 与 Executor 的调整

### 8.1 拦截器改写参数

拦截器不能继续只操作 `List<Object>`，应操作完整参数：

```text
appendParameter(name, value, source)
replaceParameter(index, parameter)
withParameters(parameters)
```

租户拦截器示例：

```text
原 SQL：DELETE FROM user WHERE id = ?
原参数：[id]

改写 SQL：DELETE FROM user WHERE id = ? AND tenant_id = ?
新参数：[id, tenantId]
```

追加操作必须由 `SqlParameterList` 或 `SqlStatement` 工厂自动重新编号。

### 8.2 Executor 绑定

`JdbcExecutor` 应新增基于 `SqlStatement` 的绑定路径：

```text
1. prepareStatement(statement.sql())
2. 遍历 statement.parameters()
3. 调用 parameter.binding/binder
4. execute
```

旧的 `Object[]` 方法继续保留给底层公共 JDBC API，但新 Builder 和实体 CRUD 不应再将 `SqlParameter` 降级成 `Object[]`。

### 8.3 分页和 SQL 改写

方言改写时必须同时处理：

```text
SQL 文本
参数顺序
参数来源
语句元数据
```

如果分页方言只是复制原参数，应保持原参数对象顺序；如果追加 offset/limit，则通过参数收集器追加，而不是直接拼装新的 `List<Object>`。

---

## 9. 表引用与 `withTable`

当前 `withTable` 使用字符串替换：

```java
sql.replace(from, to)
```

这不能保证安全处理：

- 同名表和前缀相似表；
- schema；
- alias；
- JOIN；
- 子查询；
- 字符串字面量和注释。

分阶段处理：

### 第一阶段

保留 `TableRef` 元数据，同时限制表重写只对框架生成的结构化 SQL 生效，并增加 schema、alias、role 信息。

### 第二阶段

让 Factory 输出 `SqlFragment` 或简单 SQL AST，在结构化节点上替换表名，最后统一渲染 SQL。

在完整 AST 实现前，不宣称 `withTable` 能解决任意原生 SQL 的安全替换问题。

---

## 10. 分阶段实施计划

### 阶段一：建立新参数内核

新增：

```text
SqlParameter
ParameterSource
ParameterBinding
ParameterBinder
SqlParameterList
StatementMetadata
```

重构 `SqlStatement`，同时提供旧 `List<Object>` 的兼容入口。

验收标准：

- 新旧语句都能执行；
- 参数顺序可观察；
- `SqlStatement` 集合不可变；
- 拦截器可以追加带来源的参数。

### 阶段二：拆分 TableMetaContext

先新增包装层，不立即大范围改动缓存：

```text
EntityMetadataContext → 包装 EntityTableMetaDefinition
EntitySnapshot        → 从实体读取值
MutationContext       → 保存单次操作数据
```

将 `BuilderSupport.context(entityType, values)` 改为返回操作上下文，不得向 `TableMetaContext.data` 写 values。

验收标准：

- 元数据对象不包含本次操作 values；
- 同一实体元数据可并发复用；
- listener 刷新后快照显式更新；
- null 默认值规则行为不变。

### 阶段三：实现三个 StatementFactory

按以下顺序：

```text
InsertStatementFactory
UpdateStatementFactory
DeleteStatementFactory
```

直接迁移 Creator 的成熟规则，不再调用 Creator。

验收标准：

- INSERT 生成列排除正确；
- `ignoreNull` 正确；
- UPDATE 的 SET 参数始终在 WHERE 参数之前；
- DELETE 无条件时默认拒绝；
- SQL 中 `?` 数量等于参数数量。

### 阶段四：切换新 Builder

修改：

```text
InsertBuilderImpl
UpdateBuilderImpl
DeleteBuilderImpl
BuilderSupport
```

目标调用链：

```text
Builder → StatementFactory → SqlStatement
```

Builder 不再依赖 `SqlCreator` 或 `PreparedParameterBinder`。

### 阶段五：切换 JoyaClient、JoyaJdbc 和 Mapper

优先切换写操作：

```text
save
update
deleteById
batch update
```

再切换查询操作：

```text
findById
findAll
findAllById
findListByField
分页查询
```

迁移期间禁止新增旧 Creator 调用。

### 阶段六：删除旧 Creator

完成全工程引用扫描、行为测试和文档检查后，删除：

```text
joya-core/src/main/java/.../jdbc/statment/creator/
```

然后删除所有仅用于兼容的转换代码。

---

## 13. 实施状态（已落地）

以下阶段已全部完成并通过 `mvn compile` + `mvn test`（joya-core）：

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 新参数内核：`SqlParameter` / `ParameterSource` / `SqlParameterList` / `StatementMetadata` / `ParameterBinder` / `ColumnBinding` / `BatchSqlStatement` | ✅ |
| 二 | `TableMetaContext` 拆分：`EntityMetadataContext` / `EntitySnapshot` / `MutationContext` | ✅ |
| 三 | `InsertStatementFactory` / `UpdateStatementFactory` / `DeleteStatementFactory` | ✅ |
| 四 | Builder 切换：`InsertBuilderImpl` / `UpdateBuilderImpl` / `DeleteBuilderImpl` / `BuilderSupport` | ✅ |
| 五 | `SelectStatementFactory` 新建；`JoyaClient` / `JoyaJdbc` / `JPAQueryParser` 全部切换到 Factory | ✅ |
| 六 | 删除 `jdbc/statment/creator/` 目录（9 个文件）；移除 `BuilderSupport.statement` 残留 | ✅ |

### 关键切换点

- **JoyaClient**：所有实体 CRUD 经 `EntityMetadataContext` + `StatementFactory` → `SqlStatement` → `JoyaTemplate`。
- **JoyaJdbc**（L1 旧门面，公开 API 不变）：
  - 单条 CRUD 全部走 `StatementFactory` + `jdbcExecutor`（SqlStatement 路径）。
  - 批量（`insertAll` / `updateAll` / `saveAll`）改用 `BatchSqlStatement` + `ColumnBinding`，保留 EntityListener 生命周期与 8000 行分区。
- **JPAQueryParser**（Mapper/DAO 查询解析）：4 个 Creator 分支替换为 `DeleteStatementFactory` / `UpdateStatementFactory` / `SelectStatementFactory`，仅取 `sql()` 模板（参数由 `JoyaExecutorSupport` 按位置填充，与旧行为一致）。

### 行为差异说明

- `JoyaJdbc.updateEntity` / `updateEntityIgnoreNull`：SET 列显式排除主键（旧 `UpdateBatchByIdSqlCreator` 已隐含此语义），更稳健。
- `JPAQueryParser` 的 UPDATE 模板：旧 `UpdateSqlByClassCreator` 生成所有列（含主键）SET，新实现用 `meta.columnNames()` 显式声明以保持等价；若需排除主键可改为 `updatableColumns()`。
- `SelectStatementFactory` 声明列按元数据列顺序输出（替代旧 `Set` 乱序），更稳定。

---

## 11. 必须建立的测试不变量

### 参数一致性

```text
SQL 中 ? 的数量 = parameters.size()
parameters.index 连续，从 1 开始
parameters[index - 1] 对应 SQL 中第 index 个 ?
```

### UPDATE 顺序

```text
所有 SET 参数先于所有 WHERE 参数
```

### 元数据隔离

```text
同一个 EntityMetadataContext 不因不同操作 values 而改变
```

### 重复渲染

```text
同一个 Factory 输入重复 render，SQL 和参数完全一致
```

### 不可变性

```text
修改调用方原始 Map/List 不影响已生成 SqlStatement
```

### 安全操作

```text
UPDATE/DELETE 无 WHERE 默认失败
显式 allowAll 才能执行全表操作
```

### 特殊绑定

覆盖：

```text
null
java.sql.Date
Timestamp
java.util.Date
OffsetDateTime
枚举
二进制
自定义 SqlParameterValue
```

---

## 12. 最终取舍

### 保留

```text
EntityTableMetaDefinition 的现有扫描和缓存能力
WhereCriterion / query parser 的条件语义
现有 dialect 的分页和数据库适配能力
JdbcExecutor 的连接、事务和基础执行能力
Creator 中经过验证的列筛选与参数排序规则
```

### 重构

```text
SqlStatement
PreparedParameterBinder 的参数描述和绑定语义
TableMetaContext
BuilderSupport
JoyaClient 的实体 CRUD 入口
JdbcExecutor 的 SqlStatement 执行路径
```

### 删除

```text
SqlCreator 继承体系
可变 parameters 列表
TableMetaContext.data 作为业务数据容器的用法
SqlStatement.args 的裸 Object 模型
```

最终原则：

> `TableMetaContext` 只描述“这个实体对应什么表”，`MutationContext` 只描述“这一次要做什么”，`SqlParameterList` 只负责“参数按什么顺序进入 SQL”，`SqlStatement` 只负责“把完整语句交给执行链”。

这样既删除了 Creator 的历史包袱，又保留了其最重要的参数顺序和绑定思想，并且为后续 JOIN、租户、分片、批处理和方言扩展留下可靠的演进空间。
