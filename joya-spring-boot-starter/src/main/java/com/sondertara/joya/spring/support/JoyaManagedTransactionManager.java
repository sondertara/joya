package com.sondertara.joya.spring.support;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * Joya 为自建托管数据源创建的 Spring {@link DataSourceTransactionManager} 专属标记子类：
 * 通过编译期强类型在 {@code BeanDefinition} 阶段识别 Joya 自管事务管理器，
 * 避免依赖方法名/属性字符串约定并在装配期彻底消除循环依赖。
 */
public final class JoyaManagedTransactionManager extends DataSourceTransactionManager {

    public JoyaManagedTransactionManager(DataSource dataSource) {
        super(dataSource);
    }
}
