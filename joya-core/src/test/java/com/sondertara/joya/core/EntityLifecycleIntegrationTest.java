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
 * EntityLifecycle 统一派发测试：单条与批量共享顺序，实体自身 + 全局监听器按 order 排序。
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
        public int order() {
            return 0;
        }

        @Override
        public void prePersist(Object entity) {
            EVENTS.add("entity-pre:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void postPersist(Object entity) {
            EVENTS.add("entity-post:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void preRemove(Object entity) {
            EVENTS.add("entity-preRemove:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void postRemove(Object entity) {
            EVENTS.add("entity-postRemove:" + ((LifecycleEntity) entity).getId());
        }
    }

    private static final class GlobalListener implements EntityListener {
        @Override
        public int order() {
            return 10;
        }

        @Override
        public void prePersist(Object entity) {
            EVENTS.add("global-pre:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void postPersist(Object entity) {
            EVENTS.add("global-post:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void preRemove(Object entity) {
            EVENTS.add("global-preRemove:" + ((LifecycleEntity) entity).getId());
        }

        @Override
        public void postRemove(Object entity) {
            EVENTS.add("global-postRemove:" + ((LifecycleEntity) entity).getId());
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
        joya = JoyaClient.builder(dataSource)
                .entityListener(new GlobalListener())
                .build();
        joya.sql("DROP TABLE IF EXISTS t_lifecycle").update();
        joya.sql("CREATE TABLE t_lifecycle (id BIGINT PRIMARY KEY, name VARCHAR(32))").update();
        EVENTS.clear();
    }

    @Test
    void singleInsert_entityThenGlobal() {
        joya.of(LifecycleEntity.class).insert(new LifecycleEntity(1L, "a"));
        assertEquals(List.of("entity-pre:1", "global-pre:1", "entity-post:1", "global-post:1"), EVENTS);
    }

    @Test
    void batchInsert_preAllThenPostAll() {
        joya.of(LifecycleEntity.class).insertAll(List.of(
                new LifecycleEntity(1L, "a"),
                new LifecycleEntity(2L, "b")));
        assertEquals(List.of(
                "entity-pre:1", "global-pre:1",
                "entity-pre:2", "global-pre:2",
                "entity-post:1", "global-post:1",
                "entity-post:2", "global-post:2"), EVENTS);
    }

    @Test
    void transactionInsert_entityThenGlobal() {
        joya.tx(tx -> tx.of(LifecycleEntity.class).insert(new LifecycleEntity(3L, "tx")));
        assertEquals(List.of("entity-pre:3", "global-pre:3", "entity-post:3", "global-post:3"), EVENTS);
    }

    @Test
    void deleteEntity_entityThenGlobal() {
        joya.of(LifecycleEntity.class).insert(new LifecycleEntity(1L, "a"));
        EVENTS.clear();
        joya.of(LifecycleEntity.class).delete(new LifecycleEntity(1L, "a"));
        assertEquals(List.of("entity-preRemove:1", "global-preRemove:1",
                "entity-postRemove:1", "global-postRemove:1"), EVENTS);
    }
}
