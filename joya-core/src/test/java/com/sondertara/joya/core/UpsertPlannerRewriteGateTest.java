package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-M2：原生 UPSERT 放行门禁的精细化回归测试。
 *
 * <p>验证口径：只有 {@code SELECT / INSERT / UPDATE} 三者对本实体、本表均无匹配改写器时，
 * 才允许走原生 upsert / 原生批量 upsert；任一条命中都必须回退到受行级 WHERE 保护的路径。
 */
class UpsertPlannerRewriteGateTest {

    @Entity
    @Table(name = "t_gate_row")
    public static class GateRow {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "tenant_id")
        private String tenantId;

        GateRow() {
        }

        GateRow(Long id, String name, String tenantId) {
            this.id = id;
            this.name = name;
            this.tenantId = tenantId;
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

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    /** 仅用于验证「改写器只作用于其他实体时不应误伤本实体」的元数据实体，不建表。 */
    @Entity
    @Table(name = "t_gate_other")
    public static class OtherRow {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rewrite_gate_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JoyaClient bootstrap = JoyaClient.builder(dataSource).build();
        bootstrap.sql("DROP TABLE IF EXISTS t_gate_row").update();
        bootstrap.sql("CREATE TABLE t_gate_row (id BIGINT PRIMARY KEY, name VARCHAR(32), tenant_id VARCHAR(16))").update();
        bootstrap.sql("INSERT INTO t_gate_row VALUES (1, 'a', 't1'), (2, 'b', 't2')").update();
    }

    private static SqlRewriter supportsOnly(Operation operation) {
        return new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext context) {
                return context.operation() == operation;
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext context) {
                return node;
            }
        };
    }

    private static final class StatementRecorder implements SqlExecutionListener {
        final List<String> statements = new ArrayList<>();

        @Override
        public void beforeExecute(SqlExecutionContext ctx) {
            statements.add(ctx.statement().sql());
        }
    }

    @Test
    void deleteOnlyRewriter_doesNotBlockNativeUpsertForSave() {
        StatementRecorder recorder = new StatementRecorder();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(supportsOnly(Operation.DELETE))
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).save(new GateRow(1L, "a2", "t1"));

        assertEquals(1, recorder.statements.size(), String.valueOf(recorder.statements));
        assertTrue(recorder.statements.get(0).toUpperCase().contains("MERGE"),
                recorder.statements.get(0));
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("a2", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 1L)).one().orElseThrow().getName());
        assertEquals("t1", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 1L)).one().orElseThrow().getTenantId());
    }

    @Test
    void insertOnlyRewriter_blocksNativeUpsertBecauseUpsertImpliesUpdate() {
        // 红线：UPSERT 隐含 UPDATE 语义，仅命中 INSERT 也不得放行无条件冲突更新。
        StatementRecorder recorder = new StatementRecorder();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(supportsOnly(Operation.INSERT))
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).save(new GateRow(1L, "a3", "t1"));

        assertTrue(recorder.statements.size() >= 2, String.valueOf(recorder.statements));
        assertTrue(recorder.statements.stream().noneMatch(sql -> sql.toUpperCase().contains("MERGE")),
                String.valueOf(recorder.statements));
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("a3", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 1L)).one().orElseThrow().getName());
    }

    @Test
    void selectOnlyRewriter_blocksNativeBatchUpsertForSaveAll() {
        StatementRecorder recorder = new StatementRecorder();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(supportsOnly(Operation.SELECT))
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).saveAll(List.of(
                new GateRow(1L, "a4", "t1"),
                new GateRow(2L, "b4", "t2")));

        assertTrue(recorder.statements.size() >= 2, String.valueOf(recorder.statements));
        assertTrue(recorder.statements.stream().noneMatch(sql -> sql.toUpperCase().contains("MERGE")),
                String.valueOf(recorder.statements));
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("a4", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 1L)).one().orElseThrow().getName());
        assertEquals("b4", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 2L)).one().orElseThrow().getName());
    }

    @Test
    void batchOnlyRewriter_blocksNativeBatchUpsert() {
        SqlRewriter batchOnlyRewriter = new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext context) {
                return context.isBatch() && context.operation() == Operation.INSERT;
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext context) {
                return node;
            }
        };
        StatementRecorder recorder = new StatementRecorder();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(batchOnlyRewriter)
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).saveAll(List.of(
                new GateRow(1L, "a6", "t1"),
                new GateRow(2L, "b6", "t2")));

        assertTrue(recorder.statements.size() >= 2, String.valueOf(recorder.statements));
        assertTrue(recorder.statements.stream().noneMatch(sql -> sql.toUpperCase().contains("MERGE")),
                String.valueOf(recorder.statements));
    }

    @Test
    void aliasedSelectRewriter_blocksNativeUpsert() {
        SqlRewriter aliasedSelectRewriter = new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext context) {
                return context.operation() == Operation.SELECT && "t0".equals(context.rootAlias());
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext context) {
                return node;
            }
        };
        StatementRecorder recorder = new StatementRecorder();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(aliasedSelectRewriter)
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).save(new GateRow(1L, "a7", "t1"));

        assertTrue(recorder.statements.size() >= 2, String.valueOf(recorder.statements));
        assertTrue(recorder.statements.stream().noneMatch(sql -> sql.toUpperCase().contains("MERGE")),
                String.valueOf(recorder.statements));
    }

    @Test
    void rewriterForOtherEntity_doesNotBlockNativeBatchUpsert() {
        // 只处理其他实体的改写器不应让本实体的 saveAll 失去原生批量 UPSERT。
        StatementRecorder recorder = new StatementRecorder();
        SqlRewriter otherEntityRewriter = new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext context) {
                return context.entityType() == OtherRow.class;
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext context) {
                return node;
            }
        };
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(otherEntityRewriter)
                .executionListener(recorder)
                .build();

        joya.of(GateRow.class).saveAll(List.of(
                new GateRow(1L, "a5", "t1"),
                new GateRow(2L, "b5", "t2")));

        assertEquals(1, recorder.statements.size(), String.valueOf(recorder.statements));
        assertTrue(recorder.statements.get(0).toUpperCase().contains("MERGE"),
                recorder.statements.get(0));
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("a5", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 1L)).one().orElseThrow().getName());
        assertEquals("b5", verifier.from(GateRow.class)
                .where(w -> w.eq(GateRow::getId, 2L)).one().orElseThrow().getName());
    }
}
