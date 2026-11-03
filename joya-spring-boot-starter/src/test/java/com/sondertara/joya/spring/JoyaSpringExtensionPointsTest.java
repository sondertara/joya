package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.jdbc.resultset.mapper.DefaultRowMapperFactory;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.executor.ResultType;
import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.kernel.executor.RowMapperFactory;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.spring.test.order.OrderRecord;
import com.sondertara.joya.spring.test.user.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 起步依赖对统一扩展点（{@code SqlRewriter} / {@code SqlExecutionListener} / {@code EntityListener} /
 * {@code RowMapperFactory} / {@code SqlExceptionTranslator}）的自动发现与按客户端路由：
 * 无 {@code @Qualifier} 的 Bean 对所有客户端生效，带 {@code @Qualifier("name")} 的只对同名客户端生效。
 */
class JoyaSpringExtensionPointsTest {

    /** 自定义异常，用于证明按客户端限定的 {@code SqlExceptionTranslator} 只作用于 order 库。 */
    static class OrderDbFailure extends RuntimeException {
        OrderDbFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ExtensionConfig {

        static final List<String> LISTENER_LOG = new CopyOnWriteArrayList<>();
        static final AtomicInteger GLOBAL_REWRITES = new AtomicInteger();
        static final AtomicInteger ORDER_REWRITES = new AtomicInteger();
        static final List<String> PERSISTED = new CopyOnWriteArrayList<>();
        static final AtomicInteger MAPPER_FACTORY_CALLS = new AtomicInteger();

        @Bean
        SqlExecutionListener globalListener() {
            return new SqlExecutionListener() {
                @Override
                public void beforeExecute(SqlExecutionContext ctx) {
                    LISTENER_LOG.add("G:" + (ctx.batch() ? "batch" : "single"));
                }
            };
        }

        @Bean
        @Qualifier("order")
        SqlExecutionListener orderListener() {
            return new SqlExecutionListener() {
                @Override
                public void beforeExecute(SqlExecutionContext ctx) {
                    LISTENER_LOG.add("O:" + (ctx.batch() ? "batch" : "single"));
                }
            };
        }

        @Bean
        SqlRewriter globalRewriter() {
            return (node, ctx) -> {
                GLOBAL_REWRITES.incrementAndGet();
                return node;
            };
        }

        @Bean
        @Qualifier("order")
        SqlRewriter orderRewriter() {
            return (node, ctx) -> {
                ORDER_REWRITES.incrementAndGet();
                return node;
            };
        }

        @Bean
        EntityListener globalEntityListener() {
            return new EntityListener() {
                @Override
                public void prePersist(Object entity) {
                    PERSISTED.add(entity.getClass().getSimpleName());
                }
            };
        }

        @Bean
        RowMapperFactory countingRowMapperFactory() {
            return new RowMapperFactory() {
                @Override
                public <V> RowMapper<V> create(ResultType type, Class<V> javaType) {
                    MAPPER_FACTORY_CALLS.incrementAndGet();
                    return DefaultRowMapperFactory.INSTANCE.create(type, javaType);
                }
            };
        }

        @Bean
        @Qualifier("order")
        SqlExceptionTranslator orderTranslator() {
            return (task, statement, ex) -> new OrderDbFailure("order-db: " + task, ex);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConflictingFactoryConfig {
        @Bean
        RowMapperFactory firstFactory() {
            return DefaultRowMapperFactory.INSTANCE;
        }

        @Bean
        RowMapperFactory secondFactory() {
            return DefaultRowMapperFactory.INSTANCE;
        }
    }

    private static ApplicationContextRunner runner(Class<?> config) {
        return new ApplicationContextRunner()
                .withUserConfiguration(config)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:ext_points_primary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource",
                        "joya.primary.use-spring=true",
                        "joya.datasources[0].name=order",
                        "joya.datasources[0].url=jdbc:h2:mem:ext_points_order;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "joya.datasources[0].username=sa",
                        "joya.datasources[0].password=",
                        "joya.datasources[0].type=org.h2.jdbcx.JdbcDataSource");
    }

    @BeforeEach
    void reset() {
        ExtensionConfig.LISTENER_LOG.clear();
        ExtensionConfig.GLOBAL_REWRITES.set(0);
        ExtensionConfig.ORDER_REWRITES.set(0);
        ExtensionConfig.PERSISTED.clear();
        ExtensionConfig.MAPPER_FACTORY_CALLS.set(0);
    }

    @Test
    void extensionBeans_areDiscoveredAndRoutedPerClient() {
        runner(ExtensionConfig.class).run(context -> {
            assertEquals(null, context.getStartupFailure());
            JoyaClientRegistry registry = context.getBean(JoyaClientRegistry.class);
            JoyaClient primary = registry.primary();
            JoyaClient order = registry.get("order");

            primary.sql("CREATE TABLE IF NOT EXISTS t_user_account "
                    + "(id BIGINT PRIMARY KEY, user_name VARCHAR(64) NOT NULL, age INT NOT NULL)").update();
            order.sql("CREATE TABLE IF NOT EXISTS t_order_record "
                    + "(id BIGINT PRIMARY KEY, order_no VARCHAR(64) NOT NULL, amount INT NOT NULL)").update();
            ExtensionConfig.LISTENER_LOG.clear();

            // ---- SqlExecutionListener：全局对两个库生效，限定 order 的只对 order 生效 ----
            primary.sql("SELECT 1").value(Integer.class);
            assertEquals(List.of("G:single"), ExtensionConfig.LISTENER_LOG, "primary 只命中全局监听器");
            ExtensionConfig.LISTENER_LOG.clear();
            order.sql("SELECT 1").value(Integer.class);
            assertTrue(ExtensionConfig.LISTENER_LOG.containsAll(List.of("G:single", "O:single")),
                    "order 同时命中全局与限定监听器: " + ExtensionConfig.LISTENER_LOG);
            assertEquals(2, ExtensionConfig.LISTENER_LOG.size());

            // ---- SqlRewriter：只对框架生成（带 AST）的语句生效，并按客户端路由 ----
            primary.from(UserAccount.class).count();
            assertTrue(ExtensionConfig.GLOBAL_REWRITES.get() > 0, "全局改写器作用于 primary");
            assertEquals(0, ExtensionConfig.ORDER_REWRITES.get(), "限定 order 的改写器不作用于 primary");
            int globalAfterPrimary = ExtensionConfig.GLOBAL_REWRITES.get();
            order.from(OrderRecord.class).count();
            assertTrue(ExtensionConfig.GLOBAL_REWRITES.get() > globalAfterPrimary, "全局改写器同样作用于 order");
            assertTrue(ExtensionConfig.ORDER_REWRITES.get() > 0, "限定 order 的改写器作用于 order");

            // ---- EntityListener：全局生效（两库共用）----
            UserAccount user = new UserAccount();
            user.setId(1L);
            user.setUserName("ext");
            user.setAge(1);
            primary.of(UserAccount.class).insert(user);
            assertEquals(List.of("UserAccount"), ExtensionConfig.PERSISTED);

            // ---- 批量也经过 listener ----
            ExtensionConfig.LISTENER_LOG.clear();
            UserAccount u2 = new UserAccount();
            u2.setId(2L);
            u2.setUserName("b");
            u2.setAge(2);
            UserAccount u3 = new UserAccount();
            u3.setId(3L);
            u3.setUserName("c");
            u3.setAge(3);
            primary.of(UserAccount.class).insertAll(List.of(u2, u3));
            assertTrue(ExtensionConfig.LISTENER_LOG.contains("G:batch"), ExtensionConfig.LISTENER_LOG.toString());

            // ---- RowMapperFactory：全局单例被所有客户端使用 ----
            ExtensionConfig.MAPPER_FACTORY_CALLS.set(0);
            primary.from(UserAccount.class).list();
            assertTrue(ExtensionConfig.MAPPER_FACTORY_CALLS.get() > 0, "自定义 RowMapperFactory 生效");

            // ---- SqlExceptionTranslator：限定 order，primary 仍是默认 DbException ----
            Throwable orderFailure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> order.sql("DELETE FROM no_such_table").update());
            assertInstanceOf(OrderDbFailure.class, orderFailure);
            Throwable primaryFailure = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> primary.sql("DELETE FROM no_such_table").update());
            assertInstanceOf(DbException.class, primaryFailure);
            assertNotNull(primaryFailure.getMessage());
        });
    }

    @Test
    void multipleGlobalSingletonExtensionBeans_failFastWithClearMessage() {
        runner(ConflictingFactoryConfig.class).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertTrue(root.getMessage().contains("Multiple RowMapperFactory beans"), root.getMessage());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CircularListenerConfig {
        static final AtomicInteger SELF_INJECTED_PERSIST_CALLS = new AtomicInteger();

        @Bean
        EntityListener clientInjectingEntityListener(JoyaClient joyaClient) {
            return new EntityListener() {
                @Override
                public void prePersist(Object entity) {
                    assertNotNull(joyaClient.settings());
                    SELF_INJECTED_PERSIST_CALLS.incrementAndGet();
                }
            };
        }
    }

    @Test
    void extensionBeanInjectingJoyaClient_resolvesLazilyWithoutCircularDependencyFailure() {
        CircularListenerConfig.SELF_INJECTED_PERSIST_CALLS.set(0);
        runner(CircularListenerConfig.class).run(context -> {
            assertEquals(null, context.getStartupFailure(),
                    "EntityListener 注入 JoyaClient 时不应触发 BeanCurrentlyInCreationException");
            JoyaClient primary = context.getBean(JoyaClient.class);
            primary.sql("CREATE TABLE IF NOT EXISTS t_user_account "
                    + "(id BIGINT PRIMARY KEY, user_name VARCHAR(64) NOT NULL, age INT NOT NULL)").update();
            UserAccount user = new UserAccount();
            user.setId(88L);
            user.setUserName("lazy-ext");
            user.setAge(18);
            primary.of(UserAccount.class).insert(user);
            assertEquals(1, CircularListenerConfig.SELF_INJECTED_PERSIST_CALLS.get());
        });
    }
}
