# 🥬 Joya

[![Maven Central](https://img.shields.io/maven-central/v/com.sondertara.joya/joya-core)](https://central.sonatype.com/namespace/com.sondertara.joya)
[![CI](https://github.com/sondertara/joya/actions/workflows/ci.yml/badge.svg)](https://github.com/sondertara/joya/actions/workflows/ci.yml)
![JDK](https://img.shields.io/badge/JDK-17%2B-blue)
[![License](https://img.shields.io/github/license/sondertara/joya)](https://www.apache.org/licenses/LICENSE-2.0)

> Joya 是一款面向现代 Java（JDK 17+ / Spring Boot 3）的轻量级 ORM 与流式 SQL 框架（主 ORM 与旁路多数据源一体），以**统一流式入口（`DataOps`：`from` / `of` / `sql` / `mapper`）**、**三段式类型安全查询 DSL**、**显式值事务（`Tx`）**、**标准 JPA `@Convert` / `@Version` 乐观锁**与**多数据源注册表（`JoyaClientRegistry`）**为核心设计理念。

## 🍹 项目特性

- **统一流式契约（`DataOps`）**：`JoyaClient`（自动提交）与 `Tx`（显式事务）实现同一份 `DataOps` 接口，三大数据入口——`db.from(Entity.class)`（查询 DSL，支持绑定离线 `Query.of(...)`）、`db.of(Entity.class)`（实体写与快捷读）、`db.sql(...)`（参数化原生 SQL）在事务内外无缝复用
- **单一查询入口，全形态覆盖**：从 `joya.from(Entity.class)` 出发，一脉相承覆盖完整实体、部分列装回实体、单列直取、跨表构造映射（`Record::new`）、分组聚合、宽 DTO 自动推列与分页（支持 `PageResult.map(...)` 转换）
- **标准 JPA `@Convert` 与 `@Version` 乐观锁一等支持**：内置标准 JPA `@Convert` / `AttributeConverter<X, Y>`（`jakarta` / `javax` 双栈无反射热路径，自动透传普通 DTO/Record 与 `WhereCriterion`）与 `Instant` 读写兼容；`@Version` 覆盖插入自动初值回写、单条/批量精确行计数校验（`batchExecuteExact`）、两阶段内存版本号回写、实体删除校验与 Upsert 冲突防覆盖
- **Spring Boot 3 主 ORM 与旁路共存一体**：Starter 默认内置 `spring-boot-starter-jdbc` 与 `jakarta.persistence-api`，引入驱动即可作为唯一主 ORM 开箱即用（自动排在 `Flyway`/`Liquibase` 之后）；主库默认复用 `spring.datasource`，自建主库或开启 `expose-transaction-manager=true` 的扩展库支持声明式 `@Transactional` 事务桥接；内置自建 Hikari 连接池指标绑定、Micrometer `ObservationRegistry` 链路追踪桥接与 `@JoyaTest` 数据层切片测试注解
- **免扫描跨库 `JoyaMapper` 与按需 `@JoyaMapperScan`**：内置全套单表读写删与动态分页默认方法，自定义查询用 `default` 方法组合 `query()` / `ops()` / `sql(...)`；既可通过 `joya.mapper(UserMapper.class)` 随取随用，也可标注 `@JoyaMapperScan` 直接 `@Autowired` 注入
- **显式值事务（`Tx`）**：`1 JoyaClient = 1 DataSource`、`1 Tx = 1 Connection`，废除 `ThreadLocal` 隐式污染；支持 `savepoint` 局部回滚、`afterCommit` / `afterCompletion` 回调与死锁退避重试（`retryOnDeadlock`）
- **动态条件零样板与默认防误操作**：`WhereCriterion` 默认严格非空（传 `null` 立即报错，可选条件显式使用 `*IfPresent` / `*IfNotBlank` / `*IfNotEmpty`）；`EntityUpdate` / `EntityDelete` 默认强制非空 `WHERE`（全表操作须显式 `.allowAll()`）；外部动态筛选通过 `PageQueryParams` 强制白名单防注入
- **AST 2.0 单趟编译与无旁路观测**：通过 `TableNameResolver` 在渲染期决定物理表名（覆盖分表与 schema 切换）；所有常规执行统一收口于 `ExecutionKernel`，经 AST `SqlRewriter` 与单趟 `SqlRenderer` 编译并 100% 经过 `SqlExecutionListener`（含慢 SQL 告警、敏感参数脱敏与 Micrometer Observation）

---

## 📦 引入依赖

环境要求：**JDK 17+**（Spring Boot 集成需 **Spring Boot 3.x**，实体注解同时兼容 `jakarta.persistence.*` 与 `javax.persistence.*`，不依赖 Spring Data JPA）。

### Spring Boot 3 项目

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-spring-boot-starter</artifactId>
    <version>1.2.0</version>
</dependency>
<!-- Starter 已内置 spring-boot-starter-jdbc（HikariCP + JDBC + 事务）与 jakarta.persistence-api，
     直接引入对应数据库驱动（如 mysql-connector-j / postgresql）即可作为主 ORM 开箱即用 -->
```

### 纯 Java 项目（无 Spring）

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-core</artifactId>
    <version>1.2.0</version>
</dependency>
```

---

## 🌱 Spring Boot 3 集成与多数据源

### 1. 配置文件（`application.yml`）

- **单数据源（零配置开箱即用）**：只要宿主配置了 `spring.datasource.*`，即可直接注入 `JoyaClient` 或 `JoyaClientRegistry`。
- **多数据源扩展**：主库通过 `joya.primary` 配置，扩展库通过 `joya.datasources` 数组配置（既可配置 `url/username/password` 自建旁路连接池，也可配置 `data-source-bean` 复用容器内已有的数据源 Bean）：

```yaml
# 1. Spring Boot 原生主数据源（MyBatis / JPA / JdbcTemplate 与 Joya 主库共用）
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/main_db
    username: root
    password: secret

# 2. Joya 配置
joya:
  sql-log: true
  sql-log-print-args: false       # 默认仅打印参数个数；开启后打印参数值并自动对 password/secret/token 等敏感列脱敏
  slow-sql-threshold-ms: 500      # 慢 SQL 阈值（毫秒）：即使关闭全量 sql-log，超阈值语句仍以 WARN 输出
  batch-size: 1000
  primary:
    use-spring: true              # 默认 true 复用 spring.datasource；设为 false 时在当前节点下配置独立连接池
    batch-size: 2048
  datasources:
    # 方式 A：自建旁路连接池（不注册为全局 DataSource Bean；存在 Actuator/MeterRegistry 时自动接入健康检查与 Hikari 指标）
    - name: order
      url: jdbc:mysql://localhost:3306/order_db
      username: root
      password: secret
      expose-transaction-manager: true  # 可选：暴露 @Qualifier("order") orderTransactionManager 支持 @Transactional("orderTransactionManager")
      batch-size: 512
      query-timeout-seconds: 15
    # 方式 B：复用 Spring 容器中已有的 DataSource / PlatformTransactionManager Bean
    - name: archive
      data-source-bean: archiveDataSource
      transaction-manager-bean: archiveTxManager
  observation:
    enabled: true                       # 存在 Micrometer ObservationRegistry 时自动上报 joya.sql 指标与链路追踪
    include-sql: false                  # 默认关闭高基数 db.statement 标签，按需开启
```

### 2. 依赖注入与跨库路由

Starter 会将主库注册为 `@Primary JoyaClient`，并为每个扩展库自动注册带 `@Qualifier("<name>")` 的 `JoyaClient` Bean：

```java
@Service
public class UserOrderService {

    // 1. 直接注入主库客户端（等价于 joyaRegistry.primary()）
    @Autowired
    private JoyaClient joya;

    // 2. 按 @Qualifier 直接注入指定扩展库客户端
    @Autowired
    @Qualifier("order")
    private JoyaClient orderJoya;

    // 3. 注入多数据源注册表（适合按租户/分片 key 动态路由）
    @Autowired
    private JoyaClientRegistry joyaRegistry;

    public void queryAcrossDatabases(String tenantDbKey) {
        List<User> activeUsers = joya.from(User.class)
            .where(w -> w.eq(User::getStatus, 1))
            .list();

        List<Order> orders = orderJoya.from(Order.class)
            .where(w -> w.gt(Order::getAmount, 100))
            .list();

        JoyaClient targetClient = joyaRegistry.find(tenantDbKey)
            .orElseGet(joyaRegistry::primary);
        targetClient.of(User.class).insert(newUser);
    }
}
```

> **开箱即用的 Spring 协同能力**：
> - **异常体系统一**：未自定义 `SqlExceptionTranslator` 时，默认启用惰性 `SpringSqlExceptionTranslator`，自动将 `SQLException` 翻译为 Spring `DataAccessException` 层次异常；
> - **扩展点按数据源路由**：容器中的 `SqlRewriter`、`SqlExecutionListener`、`TableNameResolver`、`RowMapperFactory`、`SqlExceptionTranslator`、`Executor.Factory` Bean 会自动发现——无 `@Qualifier` 对所有客户端生效，标注 `@Qualifier("order")` 仅作用于对应客户端。

### 3. 与 Spring `@Transactional` 无缝协作

在匹配 `DataSourceTransactionManager` 或 `JpaTransactionManager` 的数据源上，Joya 与 `@Transactional` 双向互通：

```java
@Service
public class OrderService {

    @Autowired private JoyaClient joya;
    @Autowired private OrderMyBatisMapper mybatisMapper; // 宿主原有的 MyBatis / JPA

    // 场景 1：常规 @Transactional 业务 —— 直接调用 joya，自动共享同一条物理事务连接（无需外包 joya.tx）
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(Order order, Coupon coupon) {
        mybatisMapper.insert(order);

        joya.of(Stock.class).update()
            .set(Stock::getCount, order.getRemainStock())
            .where(w -> w.eq(Stock::getSkuId, order.getSkuId()))
            .execute();

        // 场景 2：在 @Transactional 内使用 savepoint 局部容错与 afterCommit 提交后回调
        joya.tx(tx -> {
            try {
                // 发券失败仅回滚该保存点，不影响外层订单与库存提交
                tx.savepoint(sp -> sp.of(Coupon.class).insert(coupon));
            } catch (Exception ex) {
                log.warn("发券失败，已局部回滚", ex);
            }

            // 挂载到最外层 @Transactional 真正物理 commit 成功后才触发
            tx.afterCommit(() -> mqProducer.sendOrderCreated(order.getId()));
        });
    }

    // 场景 3：不标 @Transactional（避免慢操作占连接），仅落库阶段开细粒度事务；闭包内的 MyBatis 也会自动加入该事务
    public void syncOrderStatus(Long orderId) {
        RemoteStatus remote = remoteClient.queryStatus(orderId);
        joya.tx(tx -> {
            tx.of(Order.class).update()
                .set(Order::getStatus, remote.code())
                .where(w -> w.eq(Order::getId, orderId))
                .execute();
            mybatisMapper.insertStatusTrace(orderId, remote.code());
        });
    }
}
```

> **补充说明**：同一业务内的局部容错优先使用 `tx.savepoint(...)`（仅占 1 条连接）。若确需在 `@Transactional` 方法内部脱离外层回滚独立落库（如异常审计日志，免拆子 Bean 规避 Spring AOP 自调用失效），可使用 `joya.tx().requiresNew().run(innerTx -> ...)`（挂起外层事务新借连接，高并发下需留意连接池容量）。

---

## 🍱 核心用法一览

### 1. 纯 Java 创建客户端与局部派生配置（`withSettings`）

```java
// 快捷创建（复杂扩展使用 JoyaClient.builder(dataSource)...build()）
JoyaClient joya = JoyaClient.create(dataSource);

// 基于当前客户端或 Tx 零污染派生只读配置视图（共享底层连接源与缓存；在 Tx 上超时自动受事务剩余 deadline 钳制）
DataOps fastOps = joya.withSettings(s -> s.toBuilder()
    .queryTimeoutSeconds(3)
    .batchSize(500)
    .build());
```

### 2. 统一查询 DSL（`joya.from`）

遵循三段式直觉模型：**`joya.from(主表) [查哪些行: join/where/groupBy/having/orderBy/limit] [.select(挑哪些列)] .终结方法(怎么装)`**：

```java
import static com.sondertara.joya.core.query.dsl.Exprs.*;

// 1. 完整实体（可选动态条件使用 *IfPresent / *IfNotBlank / *IfNotEmpty）
List<User> users = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1).containsIfNotBlank(User::getName, keyword))
    .orderByDesc(User::getId)
    .list();

// 2. 部分列装回实体（单表与联表写法完全一致）
List<Order> orders = joya.from(Order.class)
    .join(Order::getUserId, User::getId)
    .where(w -> w.eq(User::getName, "Alice"))
    .select(Order::getId, Order::getAmount)
    .list(Order.class);

// 3. 单列直取
List<String> names = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1))
    .select(User::getName)
    .list();

// 4. 跨表 DTO / Record：按位置构造映射 + 分页与结果转换（零字符串别名；编译期校验类型与列数）
public record OrderUserVO(Long orderId, BigDecimal amount, String userName) {}

PageResult<String> summaryPage = joya.from(Order.class)
    .leftJoin(Order::getUserId, User::getId)
    .where(w -> w.gteIfPresent(Order::getAmount, minAmount))
    .orderByDesc(Order::getId)
    .select(Order::getId, Order::getAmount, User::getName)
    .page(OrderUserVO::new, PageRequest.of(0, 10))
    .map(vo -> vo.userName() + ":" + vo.amount());

// 5. 分组聚合统计（Exprs.max / min / sum / avg / count / countDistinct 可同时用于 select / having / orderBy）
public record DeptStat(String deptName, Integer maxAge, Long total) {}

List<DeptStat> stats = joya.from(User.class)
    .join(User::getDeptId, Dept::getId)
    .groupBy(Dept::getName)
    .having(h -> h.gt(count(), 5L))
    .orderByDesc(max(User::getAge))
    .select(Dept::getName, max(User::getAge), count())
    .list(DeptStat::new);

// 6. 宽 DTO：不写 select，根据 DTO 字段名自动推列
List<UserBrief> briefs = joya.from(User.class)
    .where(w -> w.eq(User::getStatus, 1))
    .list(UserBrief.class);

// 7. 统计终结方法
long count = joya.from(User.class).where(w -> w.eq(User::getStatus, 1)).count();
boolean exists = joya.from(User.class).where(w -> w.eq(User::getEmail, email)).exists();
Optional<Integer> maxAge = joya.from(User.class).max(User::getAge);

// 8. 同表多次关联 / 自连接 + 列间比较 + 类型安全列别名（as(VO::getXxx)）
List<OrderVO> orderVOs = joya.from(Order.class)
    .leftJoin(Order::getBuyerId, User::getId, "buyer")
    .leftJoin(Order::getSellerId, User::getId, "seller")
    .where(w -> w.gt(col("buyer", User::getLevel), col("seller", User::getLevel)))
    .select(
        Order::getId,
        col("buyer", User::getName).as(OrderVO::getBuyerName),
        col("seller", User::getName).as(OrderVO::getSellerName)
    )
    .list(OrderVO.class);
```

### 3. 参数化原生 SQL 与 `:name` 命名参数（`joya.sql`）

复杂报表、窗口函数或数据库特定语法可直接使用 `joya.sql(...)`，同样经过 `ExecutionKernel` 统一执行与观测管线，支持 `?` 位置参数与方言感知的 `:name` 命名参数（自动跳过字符串、注释与 PostgreSQL `::type` 类型转换）：

```java
// 1. 位置参数查询（支持映射为 Record / Bean / 标量 / Map）
List<OrderUserVO> topOrders = joya.sql(
        "SELECT o.id AS order_id, o.amount, u.name AS user_name " +
        "FROM t_order o JOIN t_user u ON o.user_id = u.id WHERE o.status = ?", 1)
    .list(OrderUserVO.class);

// 2. 命名参数绑定（bind / bindMap / bindBean；非空集合自动展开为 ?, ?, ...）
List<User> matched = joya.sql(
        "SELECT * FROM t_user WHERE status = :status AND id IN (:ids)")
    .bind("status", 1)
    .bind("ids", List.of(101L, 102L, 103L))
    .list(User.class);

// 3. 原生写操作与标量读取
long total = joya.sql("SELECT COUNT(*) FROM t_user WHERE dept_id = :deptId")
    .bindBean(queryDTO)
    .count();
```

### 4. 实体写操作与前置自监听钩子（`EntityOps` & `EntityListener`）

实体（或项目公共 `BaseEntity`）可实现零外部依赖的 `EntityListener` 接口，在持久化前自动填充审计字段或自定义主键：

```java
public abstract class AuditableEntity implements EntityListener {
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Override
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

```java
// 单条 / 批量写与 Upsert（生成键自动回写实体属性）
MutationResult res = joya.of(User.class).insert(user);
joya.of(User.class).patch(partialUser);              // 仅更新非 null 列
joya.of(User.class).saveAll(users);                  // 支持原生 UPSERT 的方言自动走批量 UPSERT

// 列级条件更新 / 删除（默认强制要求非空 WHERE，防止全表误更新/误删）
joya.of(User.class).update()
    .set(User::getStatus, 0)
    .where(w -> w.lt(User::getLastLoginAt, expireTime))
    .execute();
```

### 5. `JoyaMapper` 接口（免扫描即用 & Spring `@JoyaMapperScan` 注入） / `JoyaRepository` 基类

`JoyaMapper<T>` 内置了完整的单表快捷方法（`insert` / `update` / `patch` / `save` / `saveAll` / `findById` / `findByField` / `findByCondition` / `findAll(SortCondition.asc(User::getId))` / `findPageByCondition` / `queryPage` / `existsByCondition` / `countByCondition` / `deleteByCondition` 等），自定义查询直接用 `default` 方法组合 `query()` / `ops()` / `sql(...)`：

```java
public interface UserAccountMapper extends JoyaMapper<UserAccount> {

    default Optional<UserAccount> findByUserName(String userName) {
        return findByField(UserAccount::getUserName, userName);
    }

    default List<UserAccount> findActiveAdults() {
        return query()
            .where(w -> w.eq(UserAccount::getActive, true).gte(UserAccount::getAge, 18))
            .list();
    }
}

// 方式 A：免包扫描，在任意 JoyaClient 或事务句柄 Tx 上即取即用（内部自动缓存代理）
Optional<UserAccount> mainUser = joya.mapper(UserAccountMapper.class).findByUserName("Alice");
Optional<UserAccount> archivedUser = joyaRegistry.get("archive")
    .mapper(UserAccountMapper.class)
    .findByUserName("Alice");

// 方式 B：Spring Boot 按需开启 @JoyaMapperScan，直接 @Autowired UserAccountMapper（支持多数据源分包或接口 @Qualifier）
@JoyaMapperScan("com.example.user.mapper")
@JoyaMapperScan(basePackages = "com.example.order.mapper", client = "order")
@SpringBootApplication
public class Application {}
```

也可以采用类继承模式扩展 `JoyaRepository<T>`：

```java
public class UserAccountRepository extends JoyaRepository<UserAccount> {
    public UserAccountRepository(DataOps db) {
        super(db);
    }
}
```

### 6. 动态筛选协议适配（`PageQueryParams`）

控制器接收的动态筛选协议 `PageQueryRequest` 通过 `QueryCustomizer` 插件接入查询流，所有外部传入的过滤字段与排序字段均强制执行标识符格式与实体元数据白名单校验：

```java
PageResult<User> page = joya.from(User.class)
    .where(w -> w.eq(User::getTenantId, currentTenantId))
    .apply(PageQueryParams.of(request))
    .page(request.toPageRequest());
```

---

## 📗 开源许可证

Joya 遵守 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可证。
