package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.spring.annotation.JoyaMapperScan;
import com.sondertara.joya.spring.test.JoyaTest;
import com.sondertara.joya.spring.test.user.UserAccount;
import com.sondertara.joya.spring.test.user.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JoyaTest} 数据层切片测试端到端验证：
 * 验证仅装配数据源/事务/Joya 自动配置与 {@code @JoyaMapperScan}、跳过 {@code @Service} 业务组件、
 * 注入 {@code properties} 属性以及测试方法间 {@code @Transactional} 自动回滚隔离。
 */
@JoyaTest(properties = {
        "joya.batch-size=250",
        "spring.datasource.generate-unique-name=true"
})
@ContextConfiguration(classes = JoyaTestSliceIntegrationTest.SliceApp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JoyaTestSliceIntegrationTest {

    @SpringBootApplication(scanBasePackageClasses = JoyaTestSliceIntegrationTest.class)
    @JoyaMapperScan(basePackageClasses = UserAccountMapper.class)
    static class SliceApp {
    }

    @Service
    static class ExcludedBusinessService {
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JoyaClient joya;

    @Autowired
    private UserAccountMapper userMapper;

    @BeforeEach
    void initSchema() {
        joya.sql("""
                CREATE TABLE IF NOT EXISTS t_user_account (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64),
                    age INT
                )""").update();
    }

    @Test
    @Order(1)
    void sliceLoadsJoyaAndMapper_excludesService_andAppliesProperties() {
        assertNotNull(joya);
        assertNotNull(userMapper);
        assertEquals(250, joya.settings().executionOptions().batchSize(), "@JoyaTest(properties) 应正确注入环境");
        assertFalse(context.getBeansOfType(ExcludedBusinessService.class).size() > 0,
                "@JoyaTest 切片默认应过滤掉 @Service 业务组件");

        UserAccount u = new UserAccount();
        u.setId(501L);
        u.setUserName("slice-user");
        u.setAge(30);
        userMapper.insert(u);
        assertTrue(userMapper.findById(501L).isPresent());
    }

    @Test
    @Order(2)
    void sliceTransactionalRollbackIsolatesSiblingTestMethods() {
        // 如果上一个测试插入的 501L 已被 @Transactional 自动回滚，这里应为 0 行
        assertEquals(0L, userMapper.count(), "@JoyaTest 默认 @Transactional 应在每个测试方法结束后自动回滚");
    }
}
