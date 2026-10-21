
# 🧩 服务接入 :id=import

## 引入依赖 :id=dependency

Maven Project

```xml
<dependency>
    <groupId>com.sondertara</groupId>
    <artifactId>joya</artifactId>
    <version>0.0.7.208</version>
</dependency>
```

Gradle Project

```groovy
implementation 'com.sondertara:joya:0.0.7.208'
```

## Spring Boot 集成 :id=spring_boot

引入 `joya-spring-boot-starter` 后，只要容器中存在 `DataSource` Bean，即自动装配 `JoyaJdbc` 与 `JoyaSession`，无需额外配置：

```java
@SpringBootApplication
public class Application {
    // 注入操作入口
    @Autowired
    private JoyaOperations joyaOperations;
}
```

如需扫描 Mapper 接口（`JoyaMapper` 风格），使用 `@EnableJoyaJdbc` 指定包路径：

```java
@SpringBootApplication
@EnableJoyaJdbc(basePackages = {"com.sondertara.sql.dao"})
public class SqlDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SqlDemoApplication.class, args);
    }
}
```

## 可选配置 :id=config

```yaml
joya:
  # 是否打印sql日志
  sql-view: true
```
