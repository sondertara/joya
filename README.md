# 🥬 Joya

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/sondertara/joya/Java%20CI%20with%20Gradle) ![Maven Central](https://img.shields.io/maven-central/v/com.sondertara/joya) ![GitHub](https://img.shields.io/github/license/sondertara/joya)

> Joya 是一款面向现代 Java（JDK 17+）的轻量级 ORM 与流式 SQL 框架，以**三段式类型安全查询 DSL（`from` → 行过滤 → `select` → 终结映射）**与**显式值事务（`Tx`）**为核心设计理念。

## 🍹 项目特性

- **单一查询入口，9 种形态全覆盖**：从 `joya.from(Entity.class)` 出发，一脉相承覆盖完整实体、部分列装回实体、单列直取、跨表构造映射（`Record::new`）、分组聚合、宽 DTO 自动推列与分页
- **零字符串别名，编译期类型校验**：多表联查与聚合通过定长泛型 `Select1..8` + `Record::new` 按位置构造映射，内置 `RecordOrderVerifier` 防止同类型字段顺序颠倒
- **显式值事务（`Tx`）**：事务是可传递的一等公民值（`1 Tx = 1 Connection`），告别 `ThreadLocal` 隐式污染与自调用失效，`Tx` 与 `JoyaClient` 实现同一套 `DataOps` 接口
- **动态条件零样板与默认安全**：`WhereCriterion` 默认自动跳过 `null`、空白字符串与空集合；`Update/Delete` 强制非空 `WHERE`；外部动态筛选参数通过 `PageQueryParams` 强制实体元数据白名单防注入

## 🎯 使用前提

适用于 Java 17 及以上的项目。

## 🍱 核心用法一览

### 1. 创建客户端

```java
JoyaClient joya = JoyaClient.create(dataSource);
```

### 2. 统一查询 DSL（`joya.from`）

遵循三段式直觉模型：**`joya.from(主表) [查哪些行: join/where/groupBy/having/orderBy/limit] [.select(挑哪些列)] .终结方法(怎么装)`**：

```java
import static com.sondertara.joya.core.query.dsl.Exprs.*;

// 1. 完整实体
List<User> users = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1).contains(User::getName, keyword))
    .orderByDesc(User::getId)
    .list();

// 2. 部分实体：select 挑列 + 按名字装回实体
List<User> partialUsers = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1))
    .select(User::getId, User::getName)
    .list(User.class);

// 3. 联表后取主表部分字段：写法完全一致
List<Order> orders = joya.from(Order.class)
    .join(Order::getUserId, User::getId)
    .where(w -> w.eq(User::getName, "Alice"))
    .select(Order::getId, Order::getAmount)
    .list(Order.class);

// 4. 联表后取关联表实体：写法同样一致
List<User> buyers = joya.from(Order.class)
    .join(Order::getUserId, User::getId)
    .where(w -> w.gt(Order::getAmount, 100))
    .select(User::getId, User::getName)
    .list(User.class);

// 5. 单列直取
List<String> names = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1))
    .select(User::getName)
    .list();

// 6. 跨表 DTO / Record：按位置构造映射 + 分页（零字符串别名，编译期校验类型与列数）
public record OrderUserVO(Long orderId, BigDecimal amount, String userName) {}

PageResult<OrderUserVO> page = joya.from(Order.class)
    .leftJoin(Order::getUserId, User::getId)
    .where(w -> w.gte(Order::getAmount, minAmount))
    .orderByDesc(Order::getId)
    .select(Order::getId, Order::getAmount, User::getName)
    .page(PageRequest.of(0, 10), OrderUserVO::new);

// 7. 分组聚合统计（Exprs.max / min / sum / avg / count / countDistinct 可同时用于 select / having / orderBy）
public record DeptStat(String deptName, Integer maxAge, Long total) {}

List<DeptStat> stats = joya.from(User.class)
    .join(User::getDeptId, Dept::getId)
    .groupBy(Dept::getName)
    .having(h -> h.gt(count(), 5L))
    .orderByDesc(max(User::getAge))
    .select(Dept::getName, max(User::getAge), count())
    .list(DeptStat::new);

// 8. 宽 DTO：不写 select，根据 DTO 字段名自动推列
List<UserBrief> briefs = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1))
    .list(UserBrief.class);

// 9. 统计终结方法
long count = joya.from(User.class).where(w -> w.eq(User::getStatus, 1)).count();
boolean exists = joya.from(User.class).where(w -> w.eq(User::getEmail, email)).exists();
Optional<Integer> maxAge = joya.from(User.class).max(User::getAge);
```

### 3. 动态筛选协议适配（`PageQueryParams`）

控制器接收的动态筛选参数 `PageQueryParam` 通过 `QueryCustomizer` 插件接入查询流，所有外部字段名与排序字段自动执行实体元数据白名单校验：

```java
PageResult<User> page = joya.from(User.class)
    .where(w -> w.eq(User::getTenantId, currentTenantId))
    .apply(PageQueryParams.of(param))
    .page(param.toPageRequest());
```

### 4. 实体写操作与显式事务（`EntityOps` & `Tx`）

```java
// 单表 CRUD 与列级局部更新
MutationResult res = joya.of(User.class).insert(user);
joya.of(User.class).patch(partialUser);
joya.of(User.class).update()
    .set(User::getBalance, 500)
    .where(w -> w.eq(User::getId, 1L))
    .execute();

// 显式值事务：Tx 实现 DataOps，与 joya 写法完全一致
joya.tx(tx -> {
    tx.of(Account.class).update()
        .set(Account::getBalance, 400)
        .where(w -> w.eq(Account::getId, 1L))
        .execute();
    tx.savepoint(sp -> {
        sp.of(Order.class).insert(newOrder);
    });
});
```

## 📗 开源许可证

Joya 遵守 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。
