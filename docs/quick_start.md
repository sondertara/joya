# 🧩 服务接入 :id=import

## 引入依赖 :id=dependency

Maven Project（Spring Boot 3）

```xml
<dependency>
    <groupId>com.sondertara.joya</groupId>
    <artifactId>joya-spring-boot-starter</artifactId>
    <version>1.0.0</version>
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
