package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starter 资源生命周期与异常防泄漏回归测试。
 */
class JoyaStarterResourceHardeningTest {

    public static class TrackingCloseableDataSource extends AbstractDataSource implements AutoCloseable {

        static final AtomicInteger CLOSED_COUNT = new AtomicInteger();

        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public void close() {
            CLOSED_COUNT.incrementAndGet();
        }
    }

    @Test
    void buildSelfManaged_closesDataSourceWhenHikariPropertyBindingFails() {
        TrackingCloseableDataSource.CLOSED_COUNT.set(0);

        JoyaDataSourceProperties props = new JoyaDataSourceProperties();
        props.setType(TrackingCloseableDataSource.class);
        props.setUrl("jdbc:h2:mem:starter_leak_guard");
        props.setHikari(Map.of("unknownPoolOption", "bad-value"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JoyaDataSourceFactory.buildSelfManaged("joya.datasources[0]", props));
        assertTrue(ex.getMessage().contains("joya.datasources[0].hikari"), ex.getMessage());
        assertEquals(1, TrackingCloseableDataSource.CLOSED_COUNT.get(),
                "DataSource created before failed hikari binding must be closed immediately");
    }

    @Test
    void clientRegistryClose_isIdempotentClosesInReverseOrderAndContinuesAfterError() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:registry_close_guard;DB_CLOSE_DELAY=-1");
        JoyaClient primary = JoyaClient.create(ds);

        List<Integer> closeOrder = new ArrayList<>();
        AutoCloseable r1 = () -> closeOrder.add(1);
        AutoCloseable r2 = () -> {
            closeOrder.add(2);
            throw new AssertionError("boom-on-r2");
        };
        AutoCloseable r3 = () -> closeOrder.add(3);

        JoyaClientRegistry registry = new JoyaClientRegistry(
                JoyaClientRegistry.PRIMARY,
                Map.of(JoyaClientRegistry.PRIMARY, primary),
                List.of(r1, r2, r3),
                Map.of());

        AssertionError thrown = assertThrows(AssertionError.class, registry::close);
        assertEquals("boom-on-r2", thrown.getMessage());
        assertEquals(List.of(3, 2, 1), closeOrder,
                "All managed resources must be closed in reverse order even when an earlier resource throws Error");

        // 幂等重入：第二次 close() 不应重复触发底层资源关闭
        registry.close();
        assertEquals(List.of(3, 2, 1), closeOrder);
    }
}
