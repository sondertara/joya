# 🥬 Joya

![GitHub](https://img.shields.io/github/license/sondertara/joya)

> Joya 是一款面向现代 Java（JDK 17+ / Spring Boot 3）的轻量级旁路（Sidecar）ORM 与流式 SQL 框架，以**三段式类型安全查询 DSL（`from` → 行过滤 → `select` → 终结映射）**、**显式值事务（`Tx`）**与**零侵入多数据源注册表（`JoyaClientRegistry`）**为核心设计理念。

## 🍹 项目特性

- **单一查询入口，9 种形态全覆盖**：从 `joya.from(Entity.class)` 出发，一脉相承覆盖完整实体、部分列装回实体、单列直取、跨表构造映射（`Record::new`）、分组聚合、宽 DTO 自动推列与分页
- **零字符串别名，编译期列数/类型匹配 + 渲染期表归属校验**：多表联查与聚合通过定长泛型 `Select1..8` + `Record::new` 按位置构造映射，内置 `RecordOrderVerifier` 防止同类型字段顺序颠倒；列所属表是否已在 `FROM / JOIN` 中注册，由渲染期统一校验并给出可用表清单
- **旁路集成，零侵入共存**：在 Spring Boot 3 中直接复用原生 `spring.datasource` 与 `@Transactional` 事务连接（支持与 JPA / MyBatis 共享同一事务连接），扩展数据源通过 `joya.datasources` 数组独立配置，不污染全局 `DataSource` Bean
- **免扫描跨库 `JoyaMapper`**：通过 `joya.mapper(UserMapper.class)` 即拿即用 JPA 方法名派生查询与 `@Query` 原生 SQL，同一个实体与 Mapper 接口可在任意多个数据源上无缝复用
- **显式值事务（`Tx`）**：事务是可传递的一等公民值（`1 Tx = 1 Connection`），支持嵌套于 Spring `@Transactional` 内执行 `savepoint` 局部回滚与 `afterCommit` 钩子
- **动态条件零样板与默认安全**：`WhereCriterion` 比较条件默认严格非空（传 `null` 立即抛异常），需要"为空则跳过"时使用 `eqIfPresent` / `containsIfNotBlank` / `inIfNotEmpty`；`Update/Delete` 强制非空 `WHERE`；外部动态筛选参数通过 `PageQueryParams` 强制实体元数据白名单防注入
- **渲染期动态表名**：通过 `NamingPolicy.tableNameResolver` 在生成 SQL 时直接决定物理表名，覆盖按月分表、租户分表与 schema 切换；不做执行期字符串改写，因此不会误伤同名列或字符串常量

---

## 📦 引入依赖

环境要求：**JDK 17+**（Spring Boot 集成需 **Spring Boot 3.x**，同时兼容 `jakarta.persistence.*` 与 `javax.persistence.*` 实体注解）。

### Spring Boot 3 项目

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 纯 Java 项目（无 Spring）

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 🌱 Spring Boot 3 集成与多数据源

### 1. 配置文件（`application.yml`）

- **单数据源（零配置开箱即用）**：只要配置了 Spring Boot 原生 `spring.datasource.*`，即可直接注入 `JoyaClient` 或 `JoyaClientRegistry`。
- **多数据源扩展**：主库通过 `joya.primary` 配置（默认 `use-spring: true` 复用 `spring.datasource`），扩展库通过 `joya.datasources` 数组配置（IDE 支持直接联想补全所有属性）：

```yaml
# 1. Spring Boot 原生主数据源（JPA / MyBatis / JdbcTemplate 与 Joya 主库共用）
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/main_db
    username: root
    password: secret

# 2. Joya 配置
joya:
  sql-log: true
  batch-size: 4000
  # 主库配置（默认 use-spring=true 复用 spring.datasource；若设为 false 则在当前节点下配 url/username/password）
  primary:
    use-spring: true
    batch-size: 2048
  # 扩展数据源列表（不注册为全局 DataSource Bean，绝不干扰主 ORM）
  datasources:
    - name: order
      url: jdbc:mysql://localhost:3306/order_db
      username: root
      password: secret
      batch-size: 512
      query-timeout-seconds: 15
    - name: archive
      url: jdbc:postgresql://localhost:5432/archive_db
      username: postgres
      password: secret
```

### 2. 注入与多数据源调用

```java
@Service
public class UserOrderService {

    // 方式一：直接注入主库客户端（等价于 joyaRegistry.primary()）
    @Autowired
    private JoyaClient joya;

    // 方式二：注入多数据源注册表（支持主库与所有扩展库统一检索）
    @Autowired
    private JoyaClientRegistry joyaRegistry;

    public void queryAcrossDatabases(String tenantDbKey) {
        // 1. 操作主库
        List<User> activeUsers = joya.from(User.class)
            .where(w -> w.eq(User::getStatus, 1))
            .list();

        // 2. 操作指定扩展库（"order"）
        JoyaClient orderJoya = joyaRegistry.get("order");
        List<Order> orders = orderJoya.from(Order.class)
            .where(w -> w.gt(Order::getAmount, 100))
            .list();

        // 3. 动态路由与降级主库
        JoyaClient targetClient = joyaRegistry.find(tenantDbKey)
            .orElseGet(joyaRegistry::primary);
        targetClient.of(User.class).insert(newUser);
    }
}
```

---

## 🍱 核心用法一览

### 1. 纯 Java 创建客户端

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

// 6. 跨表 DTO / Record：按位置构造映射 + 分页（零字符串别名；编译期校验类型与列数，渲染期校验表归属）
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

### 3. `JoyaMapper` 接口（免包扫描，支持跨数据源复用）

定义继承 `JoyaMapper<T>` 的接口，即可使用 JPA 风格方法名派生查询与 `@Query` 原生 SQL，无需任何包扫描注解：

```java
public interface UserAccountMapper extends JoyaMapper<UserAccount> {

    Optional<UserAccount> findByUserName(String userName);

    List<UserAccount> findByAgeGreaterThanEqual(Integer minAge);

    long countByAgeGreaterThan(Integer age);

    @Query("SELECT id, user_name, age FROM t_user_account WHERE user_name = ?")
    Optional<UserAccount> selectByRawName(String userName);
}
```

直接通过任意 `JoyaClient` 或事务句柄 `Tx` 获取代理实例（每个客户端内部自动缓存代理对象，同一个 Mapper 接口可在不同数据源上隔离执行）：

```java
// 主库执行
Optional<UserAccount> mainUser = joya.mapper(UserAccountMapper.class).findByUserName("Alice");

// 同一个 Mapper 接口在 archive 归档库上执行
Optional<UserAccount> archivedUser = joyaRegistry.get("archive")
    .mapper(UserAccountMapper.class)
    .findByUserName("Alice");
```

### 4. 动态筛选协议适配（`PageQueryParams`）

控制器接收的动态筛选参数 `PageQueryParam` 通过 `QueryCustomizer` 插件接入查询流，所有外部字段名与排序字段自动执行实体元数据白名单校验：

```java
PageResult<User> page = joya.from(User.class)
    .where(w -> w.eq(User::getTenantId, currentTenantId))
    .apply(PageQueryParams.of(param))
    .page(param.toPageRequest());
```

### 5. 实体写操作与事务协作（`EntityOps` & `Tx` & `@Transactional`）

```java
// 单表 CRUD 与列级局部更新
MutationResult res = joya.of(User.class).insert(user);
joya.of(User.class).patch(partialUser);
joya.of(User.class).update()
    .set(User::getBalance, 500)
    .where(w -> w.eq(User::getId, 1L))
    .execute();

// 显式值事务：在 Spring @Transactional 内调用时自动复用外层事务连接，支持 savepoint 局部回滚与 afterCommit 回调
joya.tx(tx -> {
    tx.afterCommit(() -> log.info("Transaction committed!"));

    tx.of(Account.class).update()
        .set(Account::getBalance, 400)
        .where(w -> w.eq(Account::getId, 1L))
        .execute();

    // 局部保存点：异常仅回滚 savepoint 块内操作，不污染外层事务
    tx.savepoint(sp -> {
        sp.mapper(OrderMapper.class).save(newOrder);
    });
});
```

## 📗 开源许可证

Joya 遵守 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。
