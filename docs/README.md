# 🥬 Joya

![GitHub](https://img.shields.io/github/license/sondertara/joya)

> Joya 是一款面向现代 Java（JDK 17+ / Spring Boot 3）的轻量级旁路（Sidecar）ORM 与流式 SQL 框架，以**三段式类型安全查询 DSL（`from` → 行过滤 → `select` → 终结映射）**、**显式值事务（`Tx`）**与**零侵入多数据源注册表（`JoyaClientRegistry`）**为核心设计理念。

## 🍹 项目特性

- **统一流式 API（`DataOps`）**：通过 `joya.from(Entity.class)`（查询 DSL）、`joya.of(Entity.class)`（实体写与按主键快捷读）、`joya.sql(...)`（参数化原生 SQL）与 `joya.mapper(XxxMapper.class)`（免扫描 Mapper）统一自动提交与显式事务（`Tx`）上下文。
- **三段式类型安全查询 DSL**：完整实体、部分列装回实体、单列直取、跨表/聚合按位置构造映射（`Record::new`）、宽 DTO 自动按字段名推列与分页一脉相承，内置 `RecordOrderVerifier` 防止同类型列顺序颠倒。
- **极简连接与显式值事务（`1 JoyaClient = 1 DataSource`，`1 Tx = 1 Connection`）**：彻底废除 `ThreadLocal` 隐式事务污染，支持 `savepoint` 局部回滚与 `afterCommit` / `afterCompletion` 生命周期回调。
- **Spring Boot 3 旁路（Sidecar）与多数据源注册表**：主库默认复用 Spring 容器主 `DataSource` 与 `@Transactional` 事务连接，扩展库通过 `joya.datasources` 数组独立初始化并登记到 `JoyaClientRegistry`，不污染全局 `DataSource` Bean。
- **完整使用文档**：详见仓库根目录 [README.md](../README.md)。

## 🔌 参与贡献

欢迎 Fork 本项目，提交 Issues 或 Pull Requests。

## 📗 开源许可证

Joya 遵守 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可证。
