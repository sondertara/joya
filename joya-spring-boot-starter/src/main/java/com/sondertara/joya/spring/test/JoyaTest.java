package com.sondertara.joya.spring.test;

import com.sondertara.joya.spring.autoconfigure.JoyaAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaNamedClientsAutoConfiguration;
import com.sondertara.joya.spring.autoconfigure.JoyaObservationAutoConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Joya 数据访问层切片测试注解（对标 Spring Boot {@code @JdbcTest} / {@code @DataJpaTest}）：
 * <ul>
 *   <li>仅装配数据源、事务、SQL 初始化/迁移（Flyway/Liquibase）与 Joya 客户端/Mapper 自动配置，跳过 Web/Service 层；</li>
 *   <li>默认启用 {@link Transactional}（每个测试方法结束后自动回滚）与 {@link AutoConfigureTestDatabase}；</li>
 *   <li>若需直连外部或自定义测试数据库（如 Testcontainers），配合 {@code @AutoConfigureTestDatabase(replace = Replace.NONE)}。</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(JoyaTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(JoyaTypeExcludeFilter.class)
@Transactional
@AutoConfigureTestDatabase
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        LiquibaseAutoConfiguration.class,
        JoyaObservationAutoConfiguration.class,
        JoyaAutoConfiguration.class,
        JoyaNamedClientsAutoConfiguration.class
})
public @interface JoyaTest {

    /** 覆盖测试环境配置属性（格式 {@code "key=value"}）。 */
    String[] properties() default {};

    /** 是否启用默认组件过滤（默认跳过普通 {@code @Component} / {@code @Service} / {@code @Controller}）。 */
    boolean useDefaultFilters() default true;

    /** 额外包含的组件扫描过滤器。 */
    ComponentScan.Filter[] includeFilters() default {};

    /** 额外排除的组件扫描过滤器。 */
    ComponentScan.Filter[] excludeFilters() default {};

    /** 排除不需要的自动配置类。 */
    @AliasFor(annotation = ImportAutoConfiguration.class, attribute = "exclude")
    Class<?>[] excludeAutoConfiguration() default {};
}
