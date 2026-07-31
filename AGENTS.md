# 沟通规则

所有与用户的交流、代码注释与提交说明一律使用**中文**。仅在以下情况可保留英文：API 名称、代码标识符、第三方库引用、外部示例文本。

---

# Joya Core — AGENTS.md

Joya 是对 Spring Data JPA 的扩展，提供优雅、易读的原生 SQL 链式查询。

## 模块结构

```
joya-parent                             父 POM，聚合以下模块
├── joya-core                           核心 ORM + 查询引擎
├── joya-datasource                     数据源管理
├── joya-migration                      Flyway 式数据库迁移
└── joya-spring-boot-starter            Spring Boot 自动配置
```

## joya-core 包结构

所有代码位于 `com.sondertara.joya.core`（主要）及 `com.sondertara.joya.utils` / `com.sondertara.joya.exceptions`。

```
core/
├── JoyaOperations.java                 高层面向用户的操作接口
├── JoyaJdbc.java                       核心实现，implements JoyaOperations + JdbcOperations
│
├── jdbc/                               JDBC 层
│   ├── JdbcOperations.java             底层 JDBC 操作接口
│   ├── dao/                            Mapper & DAO
│   │   ├── JoyaMapper.java            CrudRepository 风格实体接口
│   │   ├── SimpleJoyaMapper.java       JoyaMapper 实现
│   │   ├── JoyaSession.java           会话上下文，创建 Mapper 实例
│   │   └── JoyaExecutorSupport.java   SQL 执行与实体映射支持
│   ├── support/                        连接管理 & 基础支持
│   │   ├── JdbcAccessor.java           DataSource 持有基类
│   │   ├── ConnectionContext.java      线程级连接上下文：引用计数、事务状态、保存点
│   │   ├── TransactionSynchronization.java  线程级 ConnectionContext 同步器
│   │   ├── TableMetaContext.java       表元数据上下文
│   │   ├── DbType.java                数据库类型枚举
│   │   └── JdbcType.java              JDBC 类型枚举
│   ├── dialect/                        SQL 方言
│   │   ├── SqlDialect.java            方言接口
│   │   ├── DefaultSqlDialect.java      默认实现
│   │   ├── SqlDialectRegister.java     方言注册表
│   │   ├── SqlBuilder.java            SQL 构建器
│   │   ├── BoundSql.java / BatchBoundSql.java  绑定 SQL
│   │   ├── PageSqlDialect.java         分页方言
│   │   └── provider/                   各数据库方言（MySql, Oracle, PostgreSql 等 18+）
│   ├── resultset/                      结果集映射
│   │   ├── RowMapper.java             行映射接口
│   │   ├── BeanRowMapper.java          实体映射
│   │   ├── MapRowMapper.java           Map 映射
│   │   ├── ArrayRowMapper.java         数组映射
│   │   ├── SingleColumnRowMapper.java  单列映射
│   │   └── ResultSetExtractor.java     结果集提取器及变体
│   ├── statment/                       预处理语句
│   │   ├── SqlProvider.java
│   │   ├── PreparedStatementCreator.java
│   │   └── creator/                    SQL 生成器
│   │       ├── SqlCreator.java          SQL 创建接口
│   │       ├── InsertSqlCreator.java
│   │       ├── UpdateSqlCreator.java
│   │       ├── DeleteSqlCreator.java
│   │       ├── SelectSqlCreator.java
│   │       ├── GenericSqlCreator.java
│   │       └── AbstractSqlCreator.java
│   ├── tran/                           事务管理
│   │   ├── TransactionManager.java     事务管理器接口
│   │   ├── LocalTransactionManager.java 线程安全本地事务管理器（传播、嵌套、保存点）
│   │   ├── Propagation.java            传播行为枚举
│   │   ├── Isolation.java              隔离级别枚举
│   │   ├── TransactionStatus.java       事务状态接口
│   │   ├── LocalTransactionStatus.java  本地事务状态实现
│   │   ├── TransactionObject.java       连接 + 数据源事务包装
│   │   ├── SyncManager.java            事务状态同步
│   │   ├── SavepointManager.java        保存点接口
│   │   ├── TransactionTemplate.java     事务模板
│   │   ├── TransactionTemplateManager.java
│   │   └── TransactionalInterceptor.java 声明式事务拦截器
│   └── callback/                       JDBC 回调
│       ├── ConnectionCallback.java
│       ├── PreparedStatementCallback.java
│       ├── StatementCallback.java
│       └── TransactionCallback.java
│
├── query/                              链式查询引擎
│   ├── NativeSqlQuery.java             查询对象
│   ├── NativeSqlQueryBuilder.java       构建器（核心，~600 行）
│   ├── NativeSqlQueryConfiguration.java 配置
│   ├── criterion/                      查询条件
│   │   ├── WhereCriterion.java          WHERE 条件
│   │   ├── WhereCondition.java          Lambda 条件（eq/ne/gt/lt/like/in…）
│   │   ├── SelectCriterion.java         SELECT 条件
│   │   └── JoinCriterion.java           连接条件
│   ├── builder/                        链式 Builder
│   │   ├── WhereBuilder.java            WHERE 构建器（eq, ne, gt, ge, lt, le, like, in, notIn, between, isNull 等）
│   │   ├── SelectBuilder.java           SELECT 构建器
│   │   ├── FromBuilder.java            FROM 构建器
│   │   ├── SetupBuilder.java           初始设置构建器
│   │   ├── LimitBuilder.java           分页构建器
│   │   └── ExtPartBuilder.java         扩展构建器
│   ├── parser/                         SQL 解析器
│   │   ├── SqlPartParser.java           解析器接口
│   │   ├── SelectPartParser.java
│   │   ├── FromPartParser.java
│   │   ├── WherePartParser.java
│   │   ├── GroupByPartParser.java
│   │   ├── HavingPartParser.java
│   │   ├── OrderByPartParser.java
│   │   ├── LimitPartParser.java
│   │   └── segment/                     SQL 段模型
│   │       ├── AbstractSegment.java
│   │       ├── SelectSegment.java
│   │       └── …
│   ├── pagination/                     分页
│   │   ├── PageParam.java
│   │   ├── PageQueryParam.java
│   │   ├── FieldParam.java
│   │   └── JoyaPageConvert.java
│   └── model/                          查询模型
│       ├── Condition.java & 子类（AndQueryCondition, OrQueryCondition, BetweenCondition, RawStringCondition）
│       ├── SelectPart.java / WherePart.java / OrderByPart.java / GroupByPart.java / FormPart.java
│       ├── TableAlias.java / ColumnAlias.java / OrderByAlias.java
│       ├── EntityTableMetaDefinition.java
│       ├── BaseEntity.java / PersistEntity.java
│       └── JoinPart.java
│
├── proxy/                              Mapper 动态代理
│   ├── JoyaMapperProxy.java            代理接口
│   ├── SingletonMapperProxy.java        单例代理
│   └── ByteBuddyMapperProxy.java        ByteBuddy 字节码代理
│
├── constant/                           常量与配置
│   ├── JoyaConst.java
│   └── JoyaConfig.java
│
├── cache/                              缓存
│   ├── CacheHolder.java
│   ├── AliasCacheHelper.java
│   ├── LocalTableMetaCache.java
│   └── CacheableDatabaseMetaProvider.java
│
├── utils/
│   ├── SqlDataUtils.java
│   ├── SqlFormatUtils.java
│   ├── LambdaUtils.java
│   └── JdbcUtils.java
│
└── exceptions/
    └── DbException.java
```

## 核心接口与职责

### Entry Points

| 接口/类 | 职责 |
|---------|------|
| `JoyaOperations` | 顶层面向业务操作接口：`find` / `findList` / `findById` / `save` / `update` / `delete` / `queryPage` / `findList(NativeSqlQuery)` / `execute` / `batchUpdate` / `saveAll` / `createTables` / `dropTables` |
| `JoyaJdbc` | `implements JoyaOperations + JdbcOperations`。整个包的核心实现类，约 1200+ 行。代理所有 JDBC 操作，包括 SQL 执行、事务回调、批量操作、表结构管理。 |
| `JoyaSession` | 会话上下文，持有 JoyaJdbc + ByteBuddyMapperProxy。通过 `buildMapper(entityType)` 或 `createMapper(mapperType)` 创建 `JoyaMapper` 实例。 |
| `JdbcOperations` | 底层 JDBC 操作接口：`execute`、`query`、`update`、`batchUpdate`、`call`、`executeStoredProcedure` |

### DAO Layer

| 接口/类 | 职责 |
|---------|------|
| `JoyaMapper<T>` | Spring Data CrudRepository 风格的实体操作接口：`save` / `findById` / `findAll` / `findAllById` / `count` / `delete` / `deleteAll` / `existsById` + JPA 风格 `findOne` / `update` |
| `SimpleJoyaMapper<T>` | `JoyaMapper` 的默认实现，委托给 `JoyaExecutorSupport` |
| `JoyaExecutorSupport` | SQL ←→ 实体映射执行器，支持分页、Lambda 条件、JOIN |

### Query Builder (Fluent API)

查询构建的核心入口：

```java
NativeSqlQuery query = NativeSqlQuery.builder()
    .select(...)
    .from(UserPo.class, ...)
    .where(w -> w.eq(UserPo::getUserName, "张三").subQuery(..., true))
    .build();
```

| 组件 | 职责 |
|------|------|
| `NativeSqlQuery` | 构建结果对象，包含 selects、from、where、groupBy、having、orderBy、limit 等 |
| `NativeSqlQueryBuilder` | 核心构建器，解析实体注解(@Table/@Column/@JoinTable)、处理别名、生成 SQL 参数（`?1, ?2`），检验字段合法性 |
| `WhereBuilder` | WHERE 条件构建，条件包括 eq/ne/gt/lt/like/in/notIn/between/isNull/isNotNull/or/subQuery/exists |
| `SelectBuilder` | SELECT 字段构建，支持 `selectAll()`、`select(field)`、`select(Class, field)` |
| `FromBuilder` | FROM 子句构建，`from(Class)`、`from(Class, Class, ...)` 解析 JOIN |
| `Operator` | 条件运算符枚举（EQ, NE, GT, GE, LT, LE, IN, NOT_IN, BETWEEN, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL, STARTS_WITH, ENDS_WITH） |

### JDBC Support Layer

#### 连接管理

| 类 | 职责 |
|----|------|
| `TransactionSynchronization` | `ThreadLocal<IdentityHashMap<DataSource, ConnectionContext>>`，提供 `getOrCreate()` / `getCurrent()` / `bind()` / `clear()` / `suspend()` / `resume()` / `release()` / `isInTransaction()` | 
| `ConnectionContext` | 连接上下文，持有一个 Connection，带引用计数、事务标记、隔离级别恢复、保存点管理 |
| `JdbcAccessor` | `DataSource` 持有基类，提供 `getConnection()`（直连，非引用计数） |

#### 事务管理

| 类/接口 | 职责 |
|---------|------|
| `TransactionManager` | 事务管理器接口：`begin()` / `commit()` / `rollBack()` / `hasTransaction()` |
| `LocalTransactionManager` | 完整实现，支持 7 种传播行为（REQUIRED/REQUIRES_NEW/NESTED/SUPPORTS/NOT_SUPPORTED/NEVER/MANDATORY）、事务栈、保存点、挂起/恢复 |
| `TransactionTemplate` | 编程式事务模板，`execute(TransactionCallback)` |
| `Transactional` + `TransactionalInterceptor` | 声明式事务支持 |
| `Propagation` | REQUIRED / REQUIRES_NEW / NESTED / SUPPORTS / NOT_SUPPORTED / NEVER / MANDATORY |
| `Isolation` | DEFAULT / READ_UNCOMMITTED / READ_COMMITTED / REPEATABLE_READ / SERIALIZABLE |

#### SQL Dialect

支持 18+ 数据库方言：MySQL, Oracle, PostgreSQL, H2, HSQL, SQLite, SQL Server, DB2, Derby, MariaDB, DM(达梦), Kingbase(金仓), Hive, Impala, Informix, Phoenix, XuGu(虚谷)

#### ResultSet Mapper

多种结果映射方式：Bean、Map、Array、SingleColumn、Stream、ListRecord

### Proxy / Mapper 生成

| 类 | 职责 |
|----|------|
| `JoyaMapperProxy` | 接口，`build(entityClass)` / `create(mapperInterface)` |
| `ByteBuddyMapperProxy` | 基于 ByteBuddy 的字节码生成实现，动态创建 `SimpleJoyaMapper` 实例 |
| `SingletonMapperProxy` | 单例模式实现 |

### Cache

| 类 | 职责 |
|----|------|
| `AliasCacheHelper` | 别名缓存（实体 → 表别名映射），约 10KB |
| `LocalTableMetaCache` | 表元数据本地缓存 |
| `CacheableDatabaseMetaProvider` | 可缓存的元数据提供者 |

## 关键架构决策

1. **统一连接体系**：`TransactionSynchronization`（`ThreadLocal<IdentityHashMap<DataSource, ConnectionContext>>`）是唯一的线程绑定点，`ConnectionContext` 整合了引用计数、事务标志、保存点管理。`JoyaJdbc`、`LocalTransactionManager`、`TransactionalInterceptor` 都通过 `TransactionSynchronization` 协调连接生命周期。

2. **引用计数连接管理**：`ConnectionContext` 通过引用计数控制物理连接生命周期 — 当引用计数归零且不在事务中时关闭连接。

3. **事务传播通过栈实现**：`LocalTransactionManager` 使用 `Deque<LocalTransactionStatus>` 管理事务嵌套，支持 NESTED（保存点）和 REQUIRES_NEW（挂起/恢复，通过 `TransactionSynchronization.suspend/resume` 切换线程绑定）。

4. **查询构建基于实体注解反射**：`NativeSqlQueryBuilder` 通过读取实体类的 `@Table`、`@Column`、`@JoinTable` 等 JPA 注解生成 SQL，同时通过 `AliasCacheHelper` 缓存表别名。

5. **参数编号占位符**：动态 SQL 使用 `?1, ?2, ...` 编号占位符，每次查询构建时递增计数。

6. **ByteBuddy 动态代理**：`ByteBuddyMapperProxy` 在运行时生成 `JoyaMapper<T>` 实现类，避免手动编写 DAO 实现。
