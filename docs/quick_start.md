
# 🧩 服务接入 :id=import

## 引入依赖 :id=dependency

Maven Project

```xml
<dependency>
    <groupId>com.sondertara</groupId>
    <artifactId>joya</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle Project

```groovy
implementation 'com.sondertara:joya:0.1.0'
```

## 新项目集成 :id=new_project

对于`Spring boot` 新项目,可以使用`@EnableJpaRepositories`注解一键接入，所有方法都由`BaseRepository`提供

```java
/**
 * @author SonderTara
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = {"com.sondertara.sql.dao"}, repositoryFactoryBeanClass = BaseRepositoryFactoryBean.class)
public class SqlDemoApplication {

    private static final Logger logger = LoggerFactory.getLogger(SqlDemoApplication.class);
    
    private static  ConfigurableApplicationContext context;

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(SqlDemoApplication.class, args);

    context=ctx;
    }

}
```

## 老项目扩展 :id=old_project

如果不想改变项目中已有的`Repository`,可以只注入`JoyaRepositoryFactoryBean`,使用`JoyaRepository`进行操作

```java
@Bean
public JoyaRepositoryFactoryBean joyaRepositoryFactoryBean(){
        return new JoyaRepositoryFactoryBean();
        }
```

> 通过 JoyaRepositoryFactoryBean 会注入 `JoyaRepository` 和`JoyaSpringContext`(一个Spring全局类)

## 可选配置 :id=config

```yaml
joya:
  # 是否打印sql日志
  sql-view: true
```
