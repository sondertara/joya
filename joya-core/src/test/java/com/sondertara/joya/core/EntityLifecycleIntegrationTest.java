package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityListener;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实体自监听回调（{@link EntityListener#prePersist()} / {@link EntityListener#preUpdate()}）集成测试：
 * 覆盖单条插入、批量插入、更新、saveAll 及事务路径，验证回调在列快照采样前执行。
 */
class EntityLifecycleIntegrationTest {

    private static final List<String> EVENTS = new ArrayList<>();

    @Entity
    @Table(name = "t_lifecycle")
    public static class LifecycleEntity implements EntityListener {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        public LifecycleEntity() {
        }

        public LifecycleEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public void prePersist() {
            EVENTS.add("prePersist:" + this.id);
            if (this.name == null) {
                this.name = "filled-prePersist";
            }
        }

        @Override
        public void preUpdate() {
            EVENTS.add("preUpdate:" + this.id);
        }
    }

    private JdbcDataSource dataSource;
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:lifecycle_test;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        joya = JoyaClient.builder(dataSource).build();
        joya.sql("DROP TABLE IF EXISTS t_lifecycle").update();
        joya.sql("CREATE TABLE t_lifecycle (id BIGINT PRIMARY KEY, name VARCHAR(32))").update();
        EVENTS.clear();
    }

    @Test
    void singleInsert_firesEntityPrePersistBeforeSnapshot() {
        joya.of(LifecycleEntity.class).insert(new LifecycleEntity(1L, null));
        assertEquals(List.of("prePersist:1"), EVENTS);
        assertEquals("filled-prePersist", joya.of(LifecycleEntity.class).findById(1L).orElseThrow().getName());
    }

    @Test
    void batchInsert_firesPrePersistForEachEntity() {
        joya.of(LifecycleEntity.class).insertAll(List.of(
                new LifecycleEntity(1L, "a"),
                new LifecycleEntity(2L, "b")));
        assertEquals(List.of("prePersist:1", "prePersist:2"), EVENTS);
    }

    @Test
    void transactionInsert_firesEntityPrePersist() {
        joya.tx(tx -> tx.of(LifecycleEntity.class).insert(new LifecycleEntity(3L, "tx")));
        assertEquals(List.of("prePersist:3"), EVENTS);
    }

    @Test
    void saveAll_firesPreUpdateOnExistingAndPrePersistOnNew() {
        joya.of(LifecycleEntity.class).insert(new LifecycleEntity(1L, "v1"));
        EVENTS.clear();

        joya.of(LifecycleEntity.class).saveAll(List.of(
                new LifecycleEntity(1L, "v2"),
                new LifecycleEntity(2L, null)));
        assertEquals(List.of("preUpdate:1", "prePersist:2"), EVENTS);
        assertEquals("v2", joya.of(LifecycleEntity.class).findById(1L).orElseThrow().getName());
        assertEquals("filled-prePersist", joya.of(LifecycleEntity.class).findById(2L).orElseThrow().getName());
    }
}
