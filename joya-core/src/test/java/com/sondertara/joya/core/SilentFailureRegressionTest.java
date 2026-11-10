package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.listener.EntityListener;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.Exprs;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.tx.IllegalTxStateException;
import com.sondertara.joya.core.tx.Tx;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静默错误修复的回归测试：结果映射类型校验、{@code or(...)} 单谓词防误用、null 子条件、
 * 原生 SQL 自带 LIMIT 的明确报错、非自增主键为空时的明确报错。
 */
class SilentFailureRegressionTest {

    @Entity
    @Table(name = "t_user")
    static class User {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "age")
        private Integer age;

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

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    /** 主键未声明 {@code @GeneratedValue}，用于验证插入前的主键校验。 */
    @Entity
    @Table(name = "t_auto")
    static class Auto {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        Auto() {
        }

        Auto(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "t_listener_entity")
    static class ListenerEntity implements EntityListener {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;
        private transient boolean postPersistCalled;

        ListenerEntity() {
        }

        ListenerEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public void prePersist(Object entity) {
            if (id == null) {
                id = 7L;
            }
            if (name == null) {
                name = "pre";
            }
        }

        @Override
        public void preUpdate(Object entity) {
            if (name == null) {
                name = "updated-by-listener";
            }
        }

        @Override
        public void postPersist(Object entity) {
            postPersistCalled = true;
        }
    }

    private JoyaClient joya;
    private final List<SqlStatement> executedStatements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_silent_failure_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        executedStatements.clear();
        joya = JoyaClient.builder(ds)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext context) {
                        executedStatements.add(context.statement());
                    }
                })
                .build();
        joya.sql("""
                DROP TABLE IF EXISTS t_user;
                CREATE TABLE t_user (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(32),
                    age INT
                );
                DROP TABLE IF EXISTS t_auto;
                CREATE TABLE t_auto (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(32)
                );
                DROP TABLE IF EXISTS t_listener_entity;
                CREATE TABLE t_listener_entity (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(32)
                );
                """).update();
        joya.sql("INSERT INTO t_user VALUES (1, 'a', 10), (2, 'b', 20)").update();
    }

    @Test
    void orWithSinglePredicate_failsFastInsteadOfSilentlyDegradingToAnd() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> joya.from(User.class)
                        .where(w -> w.eq(User::getAge, 10).or(c -> c.eq(User::getAge, 30)))
                        .list());
        assertTrue(error.getMessage().contains("or(...)"), error.getMessage());
        assertTrue(error.getMessage().contains("and(...)"), error.getMessage());
    }

    @Test
    void orGroupWithMultiplePredicates_keepsAndSemantics() {
        List<User> users = joya.from(User.class)
                .where(w -> w.eq(User::getName, "a").or(c -> c.eq(User::getAge, 10).eq(User::getAge, 99)))
                .list();
        assertEquals(1, users.size());
        assertEquals("a", users.get(0).getName());
    }

    @Test
    void orNestedWithSinglePredicate_stillWorksForPaginationSearch() {
        List<User> users = joya.from(User.class)
                .where(w -> w.eq(User::getName, "a").orNested(c -> c.eq(User::getAge, 10)))
                .list();
        assertEquals(1, users.size());
    }

    @Test
    void andWithNullLambda_onOrRoot_doesNotThrowNpe() {
        WhereCriterion criterion = new WhereCriterion(LinkType.OR).eq(User::getAge, 10);
        assertDoesNotThrow(() -> criterion.and(c -> null));
        assertEquals(1, joya.from(User.class).where(criterion).list().size());
    }

    @Test
    void listMap_isStillTheSupportedMapMapping() {
        List<Map<String, Object>> rows = joya.sql("SELECT * FROM t_user ORDER BY id").listMap();
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get("name"));
    }

    @Test
    void listWithMapClass_returnsColumnNameRows() {
        List<Map> rows = joya.sql("SELECT * FROM t_user ORDER BY id").list(Map.class);
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get("name"));
        assertEquals(10, rows.get(0).get("age"));
    }

    @Test
    void listWithLinkedHashMap_failsWithGuidanceToMapClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_user").list(LinkedHashMap.class));
        assertTrue(error.getMessage().contains("Map.class"), error.getMessage());
    }

    @Test
    void querySelectListWithMapClass_returnsColumnNameRows() {
        List<Map> rows = joya.from(User.class)
                .select(User::getId, User::getName)
                .list(Map.class);
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get("name"));
        assertEquals(1L, rows.get(0).get("id"));
    }

    @Test
    void listWithObjectClass_failsInsteadOfSilentlyTakingFirstColumn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_user").list(Object.class));
        assertTrue(error.getMessage().contains("java.lang.Object"), error.getMessage());
    }

    @Test
    void querySelectListWithObjectClass_failsInsteadOfLosingColumns() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> joya.from(User.class).select(User::getId, User::getName).list(Object.class));
        assertTrue(error.getMessage().contains("concrete DTO"), error.getMessage());
    }

    @Test
    void nativeSqlWithTrailingLimit_rejectsFirstWithClearMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> joya.sql("SELECT * FROM t_user ORDER BY id LIMIT 1").first(User.class));
        assertTrue(error.getMessage().contains("LIMIT"), error.getMessage());
    }

    @Test
    void entityInsertBuilder_appliesPrePersistBeforeSelectingColumns() {
        ListenerEntity entity = new ListenerEntity(null, null);
        joya.of(ListenerEntity.class).insert().values(entity).execute();

        ListenerEntity stored = joya.of(ListenerEntity.class).findById(7L).orElseThrow();
        assertEquals("pre", stored.getName(), "prePersist 新增的主键列与 name 列都必须写入");
        assertTrue(entity.postPersistCalled);
    }

    @Test
    void nestedAndOrGroups_keepParentheses() {
        joya.from(User.class)
                .where(w -> w.eq(User::getName, "a")
                        .andNested(c -> c.eq(User::getAge, 10)
                                .orNested(d -> d.eq(User::getAge, 20).eq(User::getAge, 30))))
                .list();
        String sql = executedStatements.get(executedStatements.size() - 1).sql();
        assertTrue(sql.contains("(t0.age = ? AND (t0.age = ? OR t0.age = ?))"), sql);
    }

    @Test
    void insertWithoutGeneratedValueAndNullPk_failsBeforeJdbc() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> joya.of(Auto.class).insert(new Auto(null, "x")));
        assertTrue(error.getMessage().contains("@GeneratedValue"), error.getMessage());

        IllegalArgumentException ignoreNullError = assertThrows(IllegalArgumentException.class,
                () -> joya.of(Auto.class).insertIgnoreNull(new Auto(null, "x")));
        assertTrue(ignoreNullError.getMessage().contains("@GeneratedValue"), ignoreNullError.getMessage());

        IllegalArgumentException fluentInsertError = assertThrows(IllegalArgumentException.class,
                () -> joya.of(Auto.class).insert().value("name", "x").execute());
        assertTrue(fluentInsertError.getMessage().contains("@GeneratedValue"), fluentInsertError.getMessage());
    }

    @Test
    void saveAndSaveAllIgnoreNull_applyLifecycleListenersBeforeSnapshotAndColumnSelection() {
        // 1. 单条原生 upsert: prePersist 写入的默认值必须进入数据库
        ListenerEntity first = new ListenerEntity(10L, null);
        joya.of(ListenerEntity.class).save(first);
        assertEquals("pre", joya.of(ListenerEntity.class).findById(10L).orElseThrow().getName());

        // 2. 单条原生 upsert 更新已存在记录: preUpdate 填充的非空值必须写入
        ListenerEntity updateExisting = new ListenerEntity(10L, null);
        joya.of(ListenerEntity.class).save(updateExisting);
        assertEquals("updated-by-listener", joya.of(ListenerEntity.class).findById(10L).orElseThrow().getName());

        // 3. saveAllIgnoreNull: prePersist 新增的非空字段必须在列分组前生效
        ListenerEntity batch1 = new ListenerEntity(20L, null);
        ListenerEntity batch2 = new ListenerEntity(21L, "explicit");
        joya.of(ListenerEntity.class).saveAllIgnoreNull(List.of(batch1, batch2));
        assertEquals("pre", joya.of(ListenerEntity.class).findById(20L).orElseThrow().getName());
        assertEquals("explicit", joya.of(ListenerEntity.class).findById(21L).orElseThrow().getName());
    }

    @Test
    void exprAsAlias_preservesAggregateFlagDuringPagination() {
        PageResult<Long> page = joya.from(User.class)
                .select(Exprs.count().as("cnt"))
                .page(PageRequest.of(0, 10));
        assertEquals(1L, page.getTotal(), "全局聚合带别名分页的 total 应为 1 行，而非底层表行数");
        assertEquals(List.of(2L), page.getData());
    }

    @Test
    void entityInsertWithExplicitNullOverride_succeedsWithoutNullPointerException() {
        User source = new User();
        source.setId(99L);
        source.setName("to-be-cleared");
        source.setAge(30);

        long affected = joya.of(User.class)
                .insert()
                .values(source)
                .value(User::getName, null)
                .execute()
                .affectedRows();
        assertEquals(1L, affected);
        User stored = joya.of(User.class).findById(99L).orElseThrow();
        assertNull(stored.getName());
        assertEquals(30, stored.getAge());
    }

    @Test
    void txCallbacks_rejectRegistrationAfterCompletionOrInsideCompletionCallback() {
        AtomicReference<Tx> capturedTx = new AtomicReference<>();
        joya.tx(tx -> {
            capturedTx.set(tx);
            tx.afterCommit(() -> {
                assertThrows(IllegalTxStateException.class, () -> tx.afterCommit(() -> {}));
            });
            tx.afterCompletion(outcome -> {
                assertThrows(IllegalTxStateException.class, () -> tx.afterCompletion(o -> {}));
            });
        });

        Tx completedTx = capturedTx.get();
        assertThrows(IllegalTxStateException.class, () -> completedTx.afterCommit(() -> {}));
        assertThrows(IllegalTxStateException.class, () -> completedTx.afterCompletion(outcome -> {}));
    }

    @Test
    void nativeSqlUpdateAndBatch_inferActualDmlOperation() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_silent_failure_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        List<Operation> observedOps = new ArrayList<>();

        JoyaClient observedClient = JoyaClient.builder(ds)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void afterExecute(SqlExecutionContext context, SqlExecutionResult result) {
                        observedOps.add(context.metadata().operation());
                    }
                })
                .build();

        observedClient.sql("/* header comment */ INSERT INTO t_auto (id, name) VALUES (?, ?)")
                .batch(List.of(new Object[]{1L, "one"}, new Object[]{2L, "two"}));
        observedClient.sql("-- line comment\nDELETE FROM t_auto WHERE id = ?", 1L).update();
        observedClient.sql("WITH src AS (SELECT 3 AS id, 'cte' AS name) INSERT INTO t_auto (id, name) SELECT id, name FROM src")
                .update();

        assertEquals(List.of(Operation.INSERT, Operation.DELETE, Operation.INSERT), observedOps);
    }

    @Test
    void txSetRollbackOnly_afterCommitOrRollback_failsFast() {
        Tx tx = joya.tx().begin();
        tx.commit();
        assertThrows(IllegalTxStateException.class, tx::setRollbackOnly);

        Tx rolledBackTx = joya.tx().begin();
        rolledBackTx.rollback();
        assertThrows(IllegalTxStateException.class, rolledBackTx::setRollbackOnly);
    }

    @Test
    void presetStatements_inheritExplicitSqlOpsDbTypeOnUpdateAndBatch() {
        executedStatements.clear();
        joya.sql(SqlStatement.update("UPDATE t_user SET name = name WHERE id = ?", 1L)
                        .withDbType(com.sondertara.joya.core.kernel.statement.DbType.h2))
                .dbType(com.sondertara.joya.core.kernel.statement.DbType.postgresql)
                .update();
        assertEquals(com.sondertara.joya.core.kernel.statement.DbType.postgresql,
                executedStatements.get(0).metadata().dbType(),
                "显式 SqlOps.dbType(...) 应覆盖 preset 语句已有 dbType");

        executedStatements.clear();
        joya.sql(SqlStatement.update("INSERT INTO t_auto (id, name) VALUES (?, ?)"))
                .dbType(com.sondertara.joya.core.kernel.statement.DbType.postgresql)
                .batch(List.of(new Object[]{100L, "preset"}, new Object[]{101L, "batch"}));
        assertEquals(com.sondertara.joya.core.kernel.statement.DbType.postgresql,
                executedStatements.get(0).metadata().dbType(),
                "preset batch 模板应继承 SqlOps.dbType(...)");
    }

    @Test
    void selectStatementFactory_honorsCustomEntityMetadataContext() {
        com.sondertara.joya.core.metadata.EntityMetadataContext customMeta =
                new com.sondertara.joya.core.metadata.EntityMetadataContext.Builder(User.class)
                        .tableName("t_custom_meta_override")
                        .primaryKey("id")
                        .column("id", "id")
                        .column("name", "name")
                        .build();
        SqlStatement stmt = com.sondertara.joya.core.statement.factory.SelectStatementFactory.create(
                customMeta,
                new com.sondertara.joya.core.query.criterion.WhereCriterion().eq("id", 1L),
                java.util.Set.of("id"),
                com.sondertara.joya.core.naming.NamingPolicy.DEFAULT);
        assertEquals("SELECT t0.id FROM t_custom_meta_override t0 WHERE t0.id = ?", stmt.sql());
    }

    @Entity
    @Table(name = "t_immutable_user")
    static class ImmutableAuditUser {
        @Id
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "created_by", updatable = false)
        private String createdBy;

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

        public String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }
    }

    @Test
    void numberedPlaceholders_inNativeSql_reorderAndExpandArgumentsProperly() {
        executedStatements.clear();
        List<User> rows = joya.sql(
                        "SELECT * FROM t_user WHERE age = ?2 AND (name = ?1 OR name = ?1)",
                        "a", 10)
                .list(User.class);
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).getId());
        SqlStatement intercepted = executedStatements.get(executedStatements.size() - 1);
        assertEquals("SELECT * FROM t_user WHERE age = ? AND (name = ? OR name = ?)", intercepted.sql());
        assertEquals(List.of(10, "a", "a"), intercepted.args());

        // 混用 ? 与 ?1 必须快速失败
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_user WHERE id = ? AND name = ?1", 1L, "a").list(User.class));
    }

    @Test
    void upsertProtectsNonUpdatableColumns_acrossDialectsAndRuntimeSave() {
        joya.sql("""
                DROP TABLE IF EXISTS t_immutable_user;
                CREATE TABLE t_immutable_user (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(32),
                    created_by VARCHAR(32)
                );
                """).update();

        ImmutableAuditUser initial = new ImmutableAuditUser();
        initial.setId(1L);
        initial.setName("v1");
        initial.setCreatedBy("creator-original");
        joya.of(ImmutableAuditUser.class).save(initial);

        ImmutableAuditUser updated = new ImmutableAuditUser();
        updated.setId(1L);
        updated.setName("v2");
        updated.setCreatedBy("attacker-overwrite");
        joya.of(ImmutableAuditUser.class).save(updated);

        ImmutableAuditUser stored = joya.of(ImmutableAuditUser.class).findById(1L).orElseThrow();
        assertEquals("v2", stored.getName());
        assertEquals("creator-original", stored.getCreatedBy(),
                "@Column(updatable = false) 字段在 save/upsert 更新分支中不得被覆盖");

        // 验证 ON_DUPLICATE_KEY 与 ON_CONFLICT 渲染同样排除 updatable = false 列
        var meta = com.sondertara.joya.core.metadata.LocalTableMetaCache.getMetadataContext(ImmutableAuditUser.class);
        var ctx = new com.sondertara.joya.core.statement.MutationContext(
                meta,
                Map.<String, Object>of("id", 1L, "name", "v2", "created_by", "attacker"),
                new WhereCriterion(),
                null,
                false,
                false,
                com.sondertara.joya.core.naming.NamingPolicy.DEFAULT);
        SqlStatement mysqlUpsert = com.sondertara.joya.core.statement.factory.UpsertStatementFactory.create(
                ctx,
                List.of("id", "name", "created_by"),
                "id",
                com.sondertara.joya.core.kernel.statement.UpsertStyle.ON_DUPLICATE_KEY,
                com.sondertara.joya.core.kernel.statement.DbType.mysql);
        assertTrue(mysqlUpsert.sql().contains("ON DUPLICATE KEY UPDATE name = VALUES(name)"), mysqlUpsert.sql());
        org.junit.jupiter.api.Assertions.assertFalse(
                mysqlUpsert.sql().contains("created_by = VALUES(created_by)"), mysqlUpsert.sql());
    }

    @Test
    void entityUpdateAndDelete_accumulateMultipleWhereCriteriaAndCallbacksInOrder() {
        WhereCriterion c1 = WhereCriterion.create().eq(User::getId, 1L);
        WhereCriterion c2 = WhereCriterion.create().eq(User::getName, "non-existent");

        long updated = joya.of(User.class)
                .update()
                .set(User::getAge, 99)
                .where(c1)
                .where(w -> w.eq(User::getAge, 10))
                .where(c2)
                .execute()
                .affectedRows();
        assertEquals(0L, updated, "多次调用 where(WhereCriterion) 应以 AND 累加，不得被后一次覆盖");
        assertEquals(10, joya.of(User.class).findById(1L).orElseThrow().getAge());

        long deleted = joya.of(User.class)
                .delete()
                .where(c1)
                .where(c2)
                .execute()
                .affectedRows();
        assertEquals(0L, deleted, "EntityDelete 多次调用 where(WhereCriterion) 同样应以 AND 累加");
        assertEquals(2L, joya.of(User.class).count());
    }

    @Test
    void mysqlLikeEscape_usesDoubleBackslashAndDoesNotSwallowSubsequentPlaceholders() {
        String mysqlSql = "SELECT * FROM t_user WHERE name LIKE ?1 ESCAPE '\\' AND age = ?2";
        assertEquals(
                "SELECT * FROM t_user WHERE name LIKE ? ESCAPE '\\' AND age = ?",
                com.sondertara.joya.core.kernel.statement.SqlFormatUtils.getJdbcStyleSql(
                        mysqlSql, com.sondertara.joya.core.kernel.statement.DbType.mysql));

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_silent_failure_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JoyaClient mysqlClient = JoyaClient.builder(ds)
                .dialect(com.sondertara.joya.core.kernel.statement.DbType.mysql)
                .build();
        SqlStatement stmt = mysqlClient.from(User.class)
                .where(w -> w.contains(User::getName, "50%_off").eq(User::getAge, 10))
                .toSqlStatement();
        assertTrue(stmt.sql().contains("LIKE ? ESCAPE '\\\\' AND t0.age = ?"), stmt.sql());
    }

    @Test
    void tableNameResolver_resolvesOncePerTableAndRejectsSqlInjectionPayloads() {
        java.util.concurrent.atomic.AtomicInteger resolveCount = new java.util.concurrent.atomic.AtomicInteger();
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_silent_failure_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JoyaClient dynamicClient = JoyaClient.builder(ds)
                .settings(JoyaSettings.builder()
                        .tableNameResolver((schema, table) -> {
                            resolveCount.incrementAndGet();
                            return table;
                        })
                        .build())
                .build();

        dynamicClient.from(User.class).list();
        assertEquals(1, resolveCount.get(), "单次查询中同一张表的 TableNameResolver 只应解析 1 次");

        resolveCount.set(0);
        dynamicClient.from(User.class).select(User::getName).list();
        assertEquals(1, resolveCount.get(), "Select 投影查询中同一张表的 TableNameResolver 只应解析 1 次");

        com.sondertara.joya.core.naming.NamingPolicy maliciousPolicy =
                com.sondertara.joya.core.naming.NamingPolicy.DEFAULT
                        .withTableNameResolver((schema, table) -> "t_user; DROP TABLE t_user; --");
        assertThrows(IllegalArgumentException.class, () -> maliciousPolicy.resolveTable(null, "t_user"));

        String quotedInjection = com.sondertara.joya.core.kernel.dialect.QuoteStyle.BACKTICK
                .quote("`t_user`; DROP TABLE t_user; --`");
        assertEquals("```t_user``; DROP TABLE t_user; --```", quotedInjection);
    }

    @Test
    void querySelect_forksQuerySpecAndProducesStructuredAstOnToSqlStatement() {
        var baseQuery = joya.from(User.class).where(w -> w.eq(User::getId, 1L));
        var nameSelect = baseQuery.select(User::getName);
        // 在 select(...) 之后继续修改原 Query，不得污染已派生的 Select1
        baseQuery.where(w -> w.eq(User::getId, 999L));
        assertEquals(List.of("a"), nameSelect.list());

        SqlStatement entityStmt = com.sondertara.joya.core.query.dsl.Query.of(User.class).toSqlStatement();
        com.sondertara.joya.core.kernel.statement.SqlNode.Select selectAst =
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                        com.sondertara.joya.core.kernel.statement.SqlNode.Select.class, entityStmt.ast());
        assertTrue(selectAst.projections().stream()
                .allMatch(n -> n instanceof com.sondertara.joya.core.kernel.statement.SqlNode.Column));
    }

    @Test
    void txWithConnection_guardsConnectionLifecycleAndTransactionControl() {
        joya.tx(tx -> {
            tx.withConnection(conn -> {
                // close() 在受管连接上应为安全 no-op，不破坏后续事务操作
                conn.close();
                assertThrows(UnsupportedOperationException.class, conn::commit);
                assertThrows(UnsupportedOperationException.class, conn::rollback);
                assertThrows(UnsupportedOperationException.class, () -> conn.setAutoCommit(true));
                return null;
            });
            assertEquals(2L, tx.from(User.class).count());
        });
    }

    @Test
    void bug1_batchExecuteWithShortDriverCountsAdvancesByChunkRowsAndNeverOverwritesPriorChunks() {
        java.util.concurrent.atomic.AtomicInteger chunkCall = new java.util.concurrent.atomic.AtomicInteger();
        PreparedStatement mockPs = (PreparedStatement) Proxy.newProxyInstance(
                SilentFailureRegressionTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeBatch" -> switch (chunkCall.incrementAndGet()) {
                        // 第 1 批（2 行）：仅返回 1 个计数 [1]，第 2 行未知应填 SUCCESS_NO_INFO (-2)
                        case 1 -> new int[]{1};
                        // 第 2 批（2 行）：返回空数组 []，两行均应填 SUCCESS_NO_INFO (-2)，且绝不覆盖第 1 批
                        case 2 -> new int[0];
                        // 第 3 批（1 行）：返回 [1]
                        default -> new int[]{1};
                    };
                    case "clearParameters", "setObject", "addBatch", "clearBatch", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Connection mockConn = (Connection) Proxy.newProxyInstance(
                SilentFailureRegressionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> mockPs;
                    case "getAutoCommit" -> true;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DataSource mockDs = (DataSource) Proxy.newProxyInstance(
                SilentFailureRegressionTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? mockConn : null);

        JoyaClient client = JoyaClient.builder(mockDs)
                .dialect(com.sondertara.joya.core.kernel.statement.DbType.postgresql)
                .settings(JoyaSettings.builder().batchSize(2).build())
                .build();

        var result = client.sql("UPDATE t_user SET age = ? WHERE id = ?")
                .batchResult(List.of(1L, 2L, 3L, 4L, 5L), id -> new Object[]{20, id});

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[]{1, java.sql.Statement.SUCCESS_NO_INFO, java.sql.Statement.SUCCESS_NO_INFO, java.sql.Statement.SUCCESS_NO_INFO, 1},
                result.batchCounts(),
                "短返回数组的分片不得覆盖前序分片计数");
        assertEquals(5L, result.affectedRows());
    }

    @Test
    void bug2_txConnectionRollbackFailureDiscardsViaProviderAndCleansFlippedAutoCommit() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean discardCalled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean autoCommitTurnedOnAfterRollbackFail = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean inTx = new java.util.concurrent.atomic.AtomicBoolean();

        Connection rawConn = (Connection) Proxy.newProxyInstance(
                SilentFailureRegressionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> false;
                    case "setAutoCommit" -> {
                        boolean target = (Boolean) args[0];
                        if (inTx.get() && target) {
                            autoCommitTurnedOnAfterRollbackFail.set(true);
                        }
                        yield null;
                    }
                    case "isReadOnly" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "rollback" -> {
                        inTx.set(true);
                        throw new SQLException("simulated rollback failure");
                    }
                    case "close" -> null;
                    default -> null;
                });
        DataSource poolWithManualCommit = (DataSource) Proxy.newProxyInstance(
                SilentFailureRegressionTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? rawConn : null);

        var dsProvider = new com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider(poolWithManualCommit);
        com.sondertara.joya.core.kernel.connection.ConnectionProvider trackingProvider =
                new com.sondertara.joya.core.kernel.connection.ConnectionProvider() {
                    @Override
                    public Connection acquire() throws SQLException {
                        return dsProvider.acquire();
                    }

                    @Override
                    public void release(Connection connection) throws SQLException {
                        dsProvider.release(connection);
                    }

                    @Override
                    public void discard(Connection connection) throws SQLException {
                        discardCalled.set(true);
                        dsProvider.discard(connection);
                    }
                };

        Connection borrowed = trackingProvider.acquire();
        var txConn = new com.sondertara.joya.core.tx.TxConnection(borrowed, trackingProvider);
        txConn.begin(com.sondertara.joya.core.tx.TxOptions.DEFAULT);
        assertThrows(SQLException.class, txConn::close);

        assertTrue(discardCalled.get(), "回滚失败关闭时必须经由 ConnectionProvider.discard 归还/清理");
        org.junit.jupiter.api.Assertions.assertFalse(
                autoCommitTurnedOnAfterRollbackFail.get(),
                "回滚失败后严禁调用 setAutoCommit(true) 触发隐式提交");

        java.lang.reflect.Field trackerField =
                com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider.class
                        .getDeclaredField("autoCommitFlips");
        trackerField.setAccessible(true);
        var tracker = trackerField.get(dsProvider);
        java.lang.reflect.Field flippedField = tracker.getClass().getDeclaredField("flipped");
        flippedField.setAccessible(true);
        java.util.Set<?> flippedSet = (java.util.Set<?>) flippedField.get(tracker);
        assertTrue(flippedSet.isEmpty(), "discard 后自动提交翻转登记必须被清理，不得泄漏连接引用");
    }

    @Test
    void bug3_bug4_pageResultNullTotalIsNegativeOneAndPageRequestGetOffsetNeverOverflowsInt() {
        var withoutTotalResult = new com.sondertara.joya.core.query.PageResult<>(List.of("a"), null, 1, 10);
        assertEquals(-1L, withoutTotalResult.total());
        org.junit.jupiter.api.Assertions.assertFalse(withoutTotalResult.hasTotal());

        var builderDefaultResult = com.sondertara.joya.core.query.PageResult.of(List.of("a")).page(1, 10).build();
        assertEquals(-1L, builderDefaultResult.total());
        org.junit.jupiter.api.Assertions.assertFalse(builderDefaultResult.hasTotal());

        var emptyResult = com.sondertara.joya.core.query.PageResult.empty(1, 10);
        assertEquals(0L, emptyResult.total());
        assertTrue(emptyResult.hasTotal());

        var hugePage = com.sondertara.joya.core.query.pagination.PageRequest.of(100_000_000, 100);
        assertEquals(10_000_000_000L, hugePage.getOffset());
        assertEquals(10_000_000_000L, hugePage.getOffset());
    }

    @Test
    void bug6_bug7_bug8_beanRowMapperAndMapRowMapperInspectResultSetMetaDataOnlyOncePerQuery() {
        java.util.concurrent.atomic.AtomicInteger getMetaDataCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger getColumnLabelCalls = new java.util.concurrent.atomic.AtomicInteger();

        joya.withConnection(realConn -> {
            try (PreparedStatement ps = realConn.prepareStatement("SELECT id AS \"t0.id\", name AS \"t0.name\", age AS \"t0.age\" FROM t_user");
                 ResultSet realRs = ps.executeQuery()) {

                ResultSetMetaData realMeta = realRs.getMetaData();
                ResultSetMetaData countingMeta = (ResultSetMetaData) Proxy.newProxyInstance(
                        SilentFailureRegressionTest.class.getClassLoader(),
                        new Class<?>[]{ResultSetMetaData.class},
                        (proxy, method, args) -> {
                            if ("getColumnLabel".equals(method.getName())) {
                                getColumnLabelCalls.incrementAndGet();
                            }
                            return method.invoke(realMeta, args);
                        });

                ResultSet countingRs = (ResultSet) Proxy.newProxyInstance(
                        SilentFailureRegressionTest.class.getClassLoader(),
                        new Class<?>[]{ResultSet.class},
                        (proxy, method, args) -> {
                            if ("getMetaData".equals(method.getName())) {
                                getMetaDataCalls.incrementAndGet();
                                return countingMeta;
                            }
                            return method.invoke(realRs, args);
                        });

                var extractor = new com.sondertara.joya.core.kernel.executor.ListRecordExtractor<>(
                        com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper.of(User.class));
                List<User> users = extractor.extract(countingRs);
                assertEquals(2, users.size());
                assertEquals(1, getMetaDataCalls.get(), "多行查询中 ResultSet.getMetaData() 只应调用 1 次");
                assertEquals(3, getColumnLabelCalls.get(), "3 列查询中 getColumnLabel 只应在首行布局阶段调用 3 次");
            }
            return null;
        });
    }
}
