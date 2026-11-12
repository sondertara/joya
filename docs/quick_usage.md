# 🎯 使用前提

适用于 **JDK 17+** 的 Java 项目（`maven.compiler.release=17`）。

- 持久化注解同时支持 `jakarta.persistence` 与 `javax.persistence` 双栈，**不依赖 Spring Data JPA**；
- 可在纯 JDBC 环境独立使用，也可通过 `joya-spring-boot-starter` 融入 Spring Boot 3 的事务与扩展点体系。
