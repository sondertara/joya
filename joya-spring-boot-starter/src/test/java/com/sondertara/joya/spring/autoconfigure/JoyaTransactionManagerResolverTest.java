package com.sondertara.joya.spring.autoconfigure;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-M3 回归：Joya 自动配置必须排在主 DataSource / 事务管理器装配之后；无事务管理器时安静回退纯 JDBC 引擎。
 */
class JoyaTransactionManagerResolverTest {

    @Test
    void autoConfigurationRunsAfterDataSourceAndTransactionAutoConfiguration() {
        AutoConfiguration annotation = JoyaAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
        assertNotNull(annotation, "JoyaAutoConfiguration 必须声明 @AutoConfiguration");
        List<String> afterName = List.of(annotation.afterName());
        assertTrue(afterName.contains("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"),
                "必须排在主 DataSource 装配之后：" + afterName);
        assertTrue(afterName.contains("org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"),
                "必须排在事务管理器装配之后：" + afterName);
    }

    @Test
    void missingTransactionManagerFallsBackToPlainJdbcEngine() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:resolver_probe");
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

        assertNull(JoyaTransactionManagerResolver.resolve(
                ds, beanFactory.getBeanProvider(PlatformTransactionManager.class)));
    }
}
