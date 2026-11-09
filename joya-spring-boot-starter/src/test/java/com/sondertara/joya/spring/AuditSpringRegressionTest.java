package com.sondertara.joya.spring;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 侧审计修复回归：外层事务连接控制守卫、循环依赖扩展点的排序可靠性。
 */
class AuditSpringRegressionTest {

    @Entity
    @Table(name = "r7")
    public static class E {
        @Id
        @Column(name = "id")
        private Long id;

        public Long getId() {
            return id;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class SimpleConfig {
    }

    static ApplicationContextRunner runner(String db) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SimpleConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + db + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource");
    }

    @Test
    void withConnection_blocksTransactionControlInsideAmbientSpringTransaction() {
        runner("audit_b4").run(ctx -> {
            JoyaClient joya = ctx.getBean(JoyaClient.class);
            PlatformTransactionManager tm = ctx.getBean(PlatformTransactionManager.class);
            new TransactionTemplate(tm).execute(status -> {
                boolean[] autoCommit = new boolean[1];
                joya.withConnection(conn -> {
                    autoCommit[0] = conn.getAutoCommit();
                    assertThrows(UnsupportedOperationException.class, conn::setSavepoint);
                    assertThrows(UnsupportedOperationException.class, conn::commit);
                    assertThrows(UnsupportedOperationException.class, () -> conn.setAutoCommit(true));
                    return null;
                });
                assertFalse(autoCommit[0], "外层事务连接必须是 autoCommit=false");
                return null;
            });
        });
    }

    public static class ARewriter implements SqlRewriter {
        @Override
        public int order() {
            return 0;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            CycleConfig.CALLS.add("A");
            return node;
        }
    }

    public static class ZRewriter implements SqlRewriter {
        @Override
        public int order() {
            return -100;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            CycleConfig.CALLS.add("Z");
            return node;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class CycleConfig {
        static final List<String> CALLS = new CopyOnWriteArrayList<>();

        @Bean
        SqlRewriter zRewriter(JoyaClient circularClient) {
            return new ZRewriter();
        }

        @Bean
        SqlRewriter aRewriter() {
            return new ARewriter();
        }
    }

    @Test
    void circularExtensionBean_keepsDeclaredOrderAtFirstExecution() {
        new ApplicationContextRunner()
                .withUserConfiguration(CycleConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:audit_b7;MODE=MYSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.datasource.type=org.h2.jdbcx.JdbcDataSource")
                .run(ctx -> {
                    assertFalse(ctx.getStartupFailure() != null, "context failed: " + ctx.getStartupFailure());
                    SqlRewriter z = ctx.getBean("zRewriter", SqlRewriter.class);
                    assertEquals(-100, z.order());
                    JoyaClient joya = ctx.getBean(JoyaClient.class);
                    joya.sql("CREATE TABLE r7 (id BIGINT PRIMARY KEY)").update();
                    CycleConfig.CALLS.clear();
                    joya.from(E.class).list();
                    assertEquals(List.of("Z", "A"), CycleConfig.CALLS,
                            "order()=-100 的改写器必须先执行");
                    assertTrue(CycleConfig.CALLS.size() == 2);
                });
    }
}
