# 🥬 Joya

![GitHub Workflow Status](https://img.shields.io/github/workflow/status/sondertara/joya/Java%20CI%20with%20Gradle) ![Maven Central](https://img.shields.io/maven-central/v/com.sondertara/joya) ![GitHub](https://img.shields.io/github/license/sondertara/joya)

> Joya 是对`Spring Data JPA` 扩展，JPA本身功能已经很强大了，但是复杂查询语句`HQL`通常都是大量字符串拼接，不利于维护和阅读，提供优雅、易读和强大的链式查询语句的`Joya`应运而生

## 🍹 项目特性

- 基于`Hibernate NativeQuery` 进行扩展,支持全字段查询和指定字段查询,支持多种风格灵活易用
- 兼容JPA，可插拔式集成，无需修改任何代码，不影响`JPA和Hibernate` 原有功能和特性
- 作为 JPA 的扩展和增强，兼容 Spring Data JPA 原有功能和各种特性
- 拥有使用原生SQL语句的极致体验
- SQL结果可返回指定对象实体,同样支持单个字段返回包装类和`String`类
- 可扩展性强,兼容其他ORM框架底层工作量小

## 🔌 参与贡献

fork本项目,添加features或bugfix,提交Pull Requests

## 📗 开源许可证

Joya 遵守 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。
