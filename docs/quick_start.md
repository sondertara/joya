# 🧩 服务接入 :id=import

## 引入依赖 :id=dependency

Maven Project（Spring Boot 3）

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- joya-spring-boot-starter 采用轻量旁路定位，不强制绑定特定连接池；
     若宿主工程尚未引入连接池（如 spring-boot-starter-data-jpa / Druid），请一并引入 spring-boot-starter-jdbc 与对应数据库驱动 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

Maven Project（纯 Java）

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Spring Boot 3 集成 :id=spring_boot

引入 `joya-spring-boot-starter` 后，默认自动复用 Spring 容器原生的 `spring.datasource` 并装配 `JoyaClient` 与 `JoyaClientRegistry`：

```java
@Service
public class UserService {

    @Autowired
    private JoyaClient joya;

    @Autowired
    private JoyaClientRegistry joyaRegistry;

    public Optional<UserAccount> findUser(String name) {
        // 使用流式 DSL
        return joya.from(UserAccount.class)
            .where(w -> w.eq(UserAccount::getUserName, name))
            .one();
    }

    public Optional<UserAccount> findFromOrderDb(String name) {
        // 使用免扫描 JoyaMapper 跨库查询
        return joyaRegistry.get("order")
            .mapper(UserAccountMapper.class)
            .findByUserName(name);
    }
}
```

## 配置说明（主库与多数据源） :id=config

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/main_db
    username: root
    password: secret

joya:
  sql-log: true
  sql-log-print-args: false     # 默认只打印参数个数；开启后打印参数值
  sql-log-redact-patterns:      # 按参数逻辑名脱敏，命中替换为 ***（默认已覆盖 password/secret/token 等）
    - "(?i).*(password|passwd|secret|token|credential).*"
  primary:
    use-spring: true       # 默认 true：复用 spring.datasource；设为 false 时在当前节点下配置独立 url/username/password
    batch-size: 2048
  datasources:
    - name: order
      url: jdbc:mysql://localhost:3306/order_db
      username: root
      password: secret
      batch-size: 512
```
