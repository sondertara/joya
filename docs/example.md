# 使用示例

Joya 提供统一的三段式类型安全查询 DSL：
**`joya.from(主表) [查哪些行: join/where/groupBy/having/orderBy/limit] [.select(挑哪些列)] .终结方法(怎么装)`**

## 1. 单表与联表查询

```java
import static com.sondertara.joya.core.query.dsl.Exprs.*;

// 完整实体
List<User> users = joya.from(User.class)
    .where(w -> w.eq(User::getUserName, "张三"))
    .list();

// 部分实体：select 挑列 + 按名字装回实体
List<User> partialUsers = joya.from(User.class)
    .where(w -> w.eq(User::getUserName, "张三"))
    .select(User::getId, User::getUserName)
    .list(User.class);

// 单列直取
List<String> names = joya.from(User.class)
    .where(w -> w.gt(User::getAge, 18))
    .select(User::getUserName)
    .list();

// 宽 DTO：不写 select，根据 DTO 字段名自动推列
List<UserBrief> briefs = joya.from(User.class)
    .where(w -> w.gt(User::getAge, 18))
    .list(UserBrief.class);
```

## 2. 多表关联与构造器位置映射

```java
public record OrderUserVO(Long orderId, BigDecimal amount, String userName) {}

// 跨表 DTO：按位置映射到 Record 构造器 + 分页
PageResult<OrderUserVO> page = joya.from(Order.class)
    .leftJoin(Order::getUserId, User::getId)
    .where(w -> w.eq(User::getUserName, "张三"))
    .orderByDesc(Order::getId)
    .select(Order::getId, Order::getAmount, User::getUserName)
    .page(PageRequest.of(0, 10), OrderUserVO::new);
```

## 3. 分组聚合统计

```java
public record DeptStat(String deptName, Integer maxAge, Long total) {}

List<DeptStat> stats = joya.from(User.class)
    .join(User::getDeptId, Dept::getId)
    .groupBy(Dept::getName)
    .having(h -> h.gt(count(), 5L))
    .orderByDesc(max(User::getAge))
    .select(Dept::getName, max(User::getAge), count())
    .list(DeptStat::new);
```

## 4. 动态参数分页（`PageQueryParam` + `PageQueryParams`）

```java
PageResult<User> pageResult = joya.from(User.class)
    .apply(PageQueryParams.of(pageQueryParam))
    .page(pageQueryParam.toPageRequest());
```
