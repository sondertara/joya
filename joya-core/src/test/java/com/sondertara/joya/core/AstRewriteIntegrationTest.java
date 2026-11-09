package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.interceptor.SqlRewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.interceptor.SqlRewriter;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.observe.SqlExecutionResult;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.statement.factory.UpsertStatementFactory;
import com.sondertara.joya.exceptions.DbException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AST-lite 与扩展点集成测试：验证 SqlRewriter 对 Query（SELECT）与 DML 的结构改写、
 * 以及 SqlExecutionListener 的 before/after 触发。
 */
class AstRewriteIntegrationTest {

    @Entity
    @Table(name = "t_ast_user")
    public static class AstUser {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "tenant_id")
        private String tenantId;

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

    @Entity
    @Table(name = "t_ast_plain")
    public static class PlainUser {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        public PlainUser() {
        }

        public PlainUser(Long id, String name) {
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
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ast_rewrite_test;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        JoyaClient bootstrap = JoyaClient.builder(dataSource).build();
        bootstrap.sql("DROP TABLE IF EXISTS t_ast_user").update();
        bootstrap.sql("CREATE TABLE t_ast_user (id BIGINT PRIMARY KEY, name VARCHAR(32), tenant_id VARCHAR(16))").update();
        bootstrap.sql("INSERT INTO t_ast_user VALUES (1, 'alice', 't1'), (2, 'alice', 't2'), (3, 'bob', 't1')").update();
        bootstrap.sql("DROP TABLE IF EXISTS t_ast_plain").update();
        bootstrap.sql("CREATE TABLE t_ast_plain (id BIGINT PRIMARY KEY, name VARCHAR(32), tenant_id VARCHAR(16))").update();
        bootstrap.sql("INSERT INTO t_ast_plain VALUES (10, 'p1', 't1'), (11, 'p2', 't2')").update();
    }

    @Test
    void selectRewriter_addsTenantPredicate() {
        List<String> statements = new ArrayList<>();
        SqlExecutionListener recorder = new SqlExecutionListener() {
            @Override
            public void beforeExecute(SqlExecutionContext ctx) {
                statements.add(ctx.statement().sql());
            }
        };
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(new TenantRewriter("t1"))
                .executionListener(recorder)
                .build();

        List<AstUser> users = joya.from(AstUser.class)
                .where(w -> w.eq(AstUser::getName, "alice"))
                .list();

        assertEquals(1, users.size());
        assertEquals("t1", users.get(0).getTenantId());
        assertTrue(statements.get(0).contains("tenant_id = ?"), statements.get(0));
    }

    @Test
    void paginatedDistinctCount_rewriterFiltersTotal() {
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(new TenantRewriter("t1"))
                .build();

        PageResult<AstUser> page = joya.from(AstUser.class)
                .distinct()
                .where(w -> w.eq(AstUser::getName, "alice"))
                .page(PageRequest.of(0, 10));

        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getData().size());
    }

    @Test
    void updateRewriter_addsTenantPredicate() {
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(new TenantRewriter("t1"))
                .build();

        AstUser user = new AstUser();
        user.setId(2L);
        user.setName("alice-changed");
        user.setTenantId("t2");
        // 按主键更新：rewriter 追加 tenant_id = 't1'，因此 t2 的行不应被更新
        joya.of(AstUser.class).update(user);

        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("alice", verifier.from(AstUser.class).where(w -> w.eq(AstUser::getId, 2L)).one().orElseThrow().getName());
    }

    @Test
    void createClient_usesFullRowMapperFactory() {
        assertEquals(3, JoyaClient.create(dataSource).from(AstUser.class).list().size());
    }

    @Test
    void exceptionTranslator_isUsed() {
        JoyaClient joya = JoyaClient.builder(dataSource)
                .exceptionTranslator((task, statement, ex) -> new DbException("custom-sql-error", ex))
                .build();

        DbException error = assertThrows(DbException.class,
                () -> joya.sql("SELECT * FROM no_such_table").list(AstUser.class));
        assertTrue(error.getMessage().contains("custom-sql-error"), error.getMessage());
    }

    @Test
    void save_usesNativeUpsertOnH2() {
        AtomicInteger statements = new AtomicInteger();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        statements.incrementAndGet();
                    }
                })
                .build();

        AstUser user = new AstUser();
        user.setId(1L);
        user.setName("alice-native");
        user.setTenantId("t1");
        joya.of(AstUser.class).save(user);

        assertEquals(1, statements.get(), "save 应使用单条原生 upsert，而不是 exists + update");
        assertEquals("alice-native", JoyaClient.builder(dataSource).build()
                .from(AstUser.class).where(w -> w.eq(AstUser::getId, 1L)).one().orElseThrow().getName());
    }

    @Test
    void saveAll_usesNativeBatchUpsertOnH2() {
        AtomicInteger statements = new AtomicInteger();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        statements.incrementAndGet();
                    }
                })
                .build();

        AstUser first = new AstUser();
        first.setId(1L);
        first.setName("alice-native-all");
        first.setTenantId("t1");
        AstUser second = new AstUser();
        second.setId(2L);
        second.setName("alice-native-all-2");
        second.setTenantId("t2");
        joya.of(AstUser.class).saveAll(List.of(first, second));

        assertEquals(1, statements.get(), "saveAll 应使用单条批量原生 upsert，而不是逐 chunk SELECT + write");
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("alice-native-all", verifier.from(AstUser.class)
                .where(w -> w.eq(AstUser::getId, 1L)).one().orElseThrow().getName());
        assertEquals("alice-native-all-2", verifier.from(AstUser.class)
                .where(w -> w.eq(AstUser::getId, 2L)).one().orElseThrow().getName());
    }

    @Test
    void batchInsertRewriter_addsTenantColumn() {
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(new InsertTenantRewriter("t1"))
                .build();
        joya.of(PlainUser.class).insertAll(List.of(
                new PlainUser(20L, "p20"),
                new PlainUser(21L, "p21")));

        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("t1", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 20L)
                .value(String.class).orElseThrow());
        assertEquals("t1", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 21L)
                .value(String.class).orElseThrow());
    }

    @Test
    void batchUpdateRewriter_filtersTenantViaExtraParameter() {
        JoyaClient joya = JoyaClient.builder(dataSource)
                .rewriter(new TenantRewriter("t1"))
                .build();

        joya.of(PlainUser.class).updateAll(List.of(
                new PlainUser(10L, "p1-updated"),
                new PlainUser(11L, "p2-updated")));

        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("p1-updated", verifier.sql("SELECT name FROM t_ast_plain WHERE id = ?", 10L)
                .value(String.class).orElseThrow());
        assertEquals("p2", verifier.sql("SELECT name FROM t_ast_plain WHERE id = ?", 11L)
                .value(String.class).orElseThrow());
    }

    @Test
    void streamListener_receivesOnStreamClosed() {
        AtomicInteger closed = new AtomicInteger();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void onStreamClosed(SqlExecutionContext ctx) {
                        closed.incrementAndGet();
                    }
                })
                .build();

        try (java.util.stream.Stream<AstUser> stream = joya.from(AstUser.class).stream()) {
            assertEquals(3, stream.count());
        }

        assertEquals(1, closed.get(), "stream 关闭时应触发 onStreamClosed 一次");
    }

    @Test
    void executionListener_receivesBeforeAndAfter() {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        List<Long> elapsed = new ArrayList<>();
        JoyaClient joya = JoyaClient.builder(dataSource)
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        before.incrementAndGet();
                    }

                    @Override
                    public void afterExecute(SqlExecutionContext ctx, SqlExecutionResult result) {
                        after.incrementAndGet();
                        elapsed.add(result.elapsedNanos());
                    }
                })
                .build();

        joya.from(AstUser.class).list();

        assertEquals(1, before.get());
        assertEquals(1, after.get());
        assertTrue(elapsed.get(0) >= 0L);
    }

    @Test
    void joinOnAndLikePredicates_produceStructuredAstWithoutRawHoles() {
        JoyaClient joya = JoyaClient.builder(dataSource).build();
        SqlStatement stmt = joya.from(AstUser.class)
                .leftJoin(AstUser::getId, PlainUser::getId, on -> on.like(PlainUser::getName, "p%"))
                .where(w -> w.eq(AstUser::getName, PlainUser::getName)
                        .notLike(AstUser::getTenantId, "x%")
                        .contains(AstUser::getName, "a%b"))
                .toSqlStatement();

        SqlNode.Select select = (SqlNode.Select) stmt.ast();
        SqlNode.From from = (SqlNode.From) select.from();
        assertEquals(1, from.joins().size());
        SqlNode.Join join = from.joins().get(0);
        assertEquals("t_ast_plain", join.table().logicalName());
        assertTrue(join.on() instanceof SqlNode.Binary, "JOIN ON 应为结构化 Binary 节点");
        SqlNode.Binary joinAnd = (SqlNode.Binary) join.on();
        assertTrue(joinAnd.left() instanceof SqlNode.Binary eq
                && "=".equals(eq.op())
                && eq.left() instanceof SqlNode.Column
                && eq.right() instanceof SqlNode.Column, "JOIN 等值键应为 Binary('=', Column, Column)");
        assertTrue(joinAnd.right() instanceof SqlNode.Like joinLike && !joinLike.negated() && !joinLike.escaped(),
                "JOIN ON 附加 like 条件应为 SqlNode.Like");

        SqlNode.Binary whereRoot = (SqlNode.Binary) select.where();
        SqlNode.Binary leftPair = (SqlNode.Binary) whereRoot.left();
        assertTrue(leftPair.left() instanceof SqlNode.Binary colEq
                && colEq.left() instanceof SqlNode.Column
                && colEq.right() instanceof SqlNode.Column, "双列比较应为 Binary('=', Column, Column)");
        assertTrue(leftPair.right() instanceof SqlNode.Like notLike && notLike.negated() && !notLike.escaped(),
                "notLike 应统一为 SqlNode.Like");
        assertTrue(whereRoot.right() instanceof SqlNode.Like containsLike && !containsLike.negated() && containsLike.escaped(),
                "含通配符的 contains 应产出 escaped=true 的 SqlNode.Like");
    }

    @Test
    void rewriterAddingJoin_refreshesStatementMetadataTables_whileTableRenameFailsFast() {
        List<java.util.Set<TableRef>> observedTables = new ArrayList<>();
        JoyaClient joinAddingClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Select select && select.from() instanceof SqlNode.From from) {
                        List<SqlNode.Join> joins = new ArrayList<>(from.joins());
                        joins.add(new SqlNode.Join("INNER JOIN",
                                SqlNodes.table("t_ast_plain", "t_ast_plain", "t_acl"),
                                SqlNodes.eq(SqlNodes.column("t0", "id"), SqlNodes.column("t_acl", "id"))));
                        return new SqlNode.Select(select.distinct(), select.projections(),
                                new SqlNode.From(from.root(), joins),
                                select.where(), select.groupBy(), select.having(), select.orderBy());
                    }
                    return node;
                })
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        observedTables.add(ctx.statement().metadata().tables());
                    }
                })
                .build();

        joinAddingClient.from(AstUser.class).list();
        assertEquals(1, observedTables.size());
        assertTrue(observedTables.get(0).contains(TableRef.of("t_ast_user", "t0")));
        assertTrue(observedTables.get(0).contains(TableRef.of("t_ast_plain", "t_acl")),
                "改写器新增的 JOIN 表应被回收进 StatementMetadata.tables()");

        JoyaClient renamingClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Select select) {
                        return new SqlNode.Select(select.distinct(), select.projections(),
                                new SqlNode.From(SqlNodes.table("t_ast_user", "t_ast_user_202610", "t0"), List.of()),
                                select.where(), select.groupBy(), select.having(), select.orderBy());
                    }
                    if (node instanceof SqlNode.Insert insert) {
                        return new SqlNode.Insert(SqlNodes.table("t_ast_plain", "t_ast_plain_202610"),
                                insert.columns(), insert.rows());
                    }
                    return node;
                })
                .build();

        SqlRewriteException selectRenameErr = assertThrows(SqlRewriteException.class,
                () -> renamingClient.from(AstUser.class).list());
        assertTrue(selectRenameErr.getMessage().contains("tableNameResolver"), selectRenameErr.getMessage());

        SqlRewriteException batchRenameErr = assertThrows(SqlRewriteException.class,
                () -> renamingClient.of(PlainUser.class).insertAll(List.of(new PlainUser(30L, "p30"))));
        assertTrue(batchRenameErr.getMessage().contains("tableNameResolver"), batchRenameErr.getMessage());
    }

    @Test
    void batchUpsert_preservesUpsertAstAndSupportsBatchRewriter() {
        BatchSqlStatement batch = UpsertStatementFactory.createBatch(
                LocalTableMetaCache.getMetadataContext(PlainUser.class),
                List.of("id", "name"),
                List.of(new Object[]{40L, "u40"}, new Object[]{41L, "u41"}),
                NamingPolicy.DEFAULT,
                100,
                "id",
                UpsertStyle.MERGE);
        assertTrue(batch.ast() instanceof SqlNode.Upsert, "批量 UPSERT 必须保留 SqlNode.Upsert AST");

        // 直接使用仅匹配 SqlNode.Insert 的 InsertTenantRewriter，验证 StatementPreparer 自动解包/重包装 Upsert
        com.sondertara.joya.core.kernel.ExecutionKernel kernel =
                new com.sondertara.joya.core.kernel.ExecutionKernel(new com.sondertara.joya.core.kernel.ExecutionKernel.Config(
                        List.of(new InsertTenantRewriter("t_up")),
                        List.of(),
                        new com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider(dataSource, 0),
                        com.sondertara.joya.core.kernel.dialect.DialectStrategy.cached(dataSource), null, null, null, null, null));

        kernel.batchExecute(batch);

        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("t_up", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 40L)
                .value(String.class).orElseThrow());
        assertEquals("t_up", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 41L)
                .value(String.class).orElseThrow());
    }

    @Test
    void saveAndSaveAll_workWithTenantAndInsertRewriters_andRejectBatchColumnReorder() {
        // 1. 单条 save + Insert-only 租户改写器：自动作用于 SqlNode.Upsert 的内层 Insert
        JoyaClient insertTenantClient = JoyaClient.builder(dataSource)
                .rewriter(new InsertTenantRewriter("t_save"))
                .build();
        insertTenantClient.of(PlainUser.class).save(new PlainUser(50L, "saved-50"));
        JoyaClient verifier = JoyaClient.builder(dataSource).build();
        assertEquals("t_save", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 50L)
                .value(String.class).orElseThrow());

        // 2. saveAll + TenantRewriter + InsertTenantRewriter：findExistingIds 携带 t0 别名与结构化表节点
        JoyaClient fullTenantClient = JoyaClient.builder(dataSource)
                .rewriter(new TenantRewriter("t1"))
                .rewriter(new InsertTenantRewriter("t1"))
                .build();
        fullTenantClient.of(PlainUser.class).saveAll(List.of(
                new PlainUser(10L, "p1-updated"),
                new PlainUser(51L, "p51-new")
        ));
        assertEquals("p1-updated", verifier.sql("SELECT name FROM t_ast_plain WHERE id = ?", 10L)
                .value(String.class).orElseThrow());
        assertEquals("t1", verifier.sql("SELECT tenant_id FROM t_ast_plain WHERE id = ?", 51L)
                .value(String.class).orElseThrow());

        // 3. 批量 INSERT 改写器重排原有列顺序或前置插入 WHERE 参数时，显式抛 SqlRewriteException 拒绝静默错位
        JoyaClient reorderInsertClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Insert insert) {
                        List<String> reversedCols = List.of("name", "id");
                        return new SqlNode.Insert(insert.table(), reversedCols, insert.rows());
                    }
                    return node;
                })
                .build();
        assertThrows(SqlRewriteException.class,
                () -> reorderInsertClient.of(PlainUser.class)
                        .insertAll(List.of(new PlainUser(60L, "p60"), new PlainUser(61L, "p61"))));

        JoyaClient prependWhereUpdateClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Update update) {
                        SqlNode tenantPred = SqlNodes.eq(
                                SqlNodes.column((SqlNode.Table) null, "tenant_id"),
                                SqlNodes.param("tenantId", "t1", ParameterSource.TENANT));
                        // 把新参数插在原 WHERE id = ? 之前
                        return update.withWhere(SqlNodes.and(tenantPred, update.where()));
                    }
                    return node;
                })
                .build();
        assertThrows(SqlRewriteException.class,
                () -> prependWhereUpdateClient.of(PlainUser.class).updateAll(List.of(new PlainUser(10L, "x"))));
    }

    @Test
    void batchRewrite_enforcesShapeAndDistinctColumns_andSupportsFilterWorks() {
        // 1. 把 Upsert 改写为纯 Insert：单条与批量都必须被 validateRewrittenShape 拦截
        SqlRewriter unwrapUpsertRewriter = (node, ctx) -> {
            if (node instanceof SqlNode.Upsert upsert) {
                return upsert.insert();
            }
            return node;
        };
        JoyaClient unwrapClient = JoyaClient.builder(dataSource).rewriter(unwrapUpsertRewriter).build();
        assertThrows(SqlRewriteException.class,
                () -> unwrapClient.of(PlainUser.class).save(new PlainUser(70L, "u70")));
        BatchSqlStatement upsertBatch = UpsertStatementFactory.createBatch(
                LocalTableMetaCache.getMetadataContext(PlainUser.class),
                List.of("id", "name"),
                List.<Object[]>of(new Object[]{71L, "u71"}),
                NamingPolicy.DEFAULT,
                100,
                "id",
                UpsertStyle.MERGE);
        com.sondertara.joya.core.kernel.ExecutionKernel unwrapKernel =
                new com.sondertara.joya.core.kernel.ExecutionKernel(new com.sondertara.joya.core.kernel.ExecutionKernel.Config(
                        List.of(unwrapUpsertRewriter),
                        List.of(),
                        new com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider(dataSource, 0),
                        com.sondertara.joya.core.kernel.dialect.DialectStrategy.cached(dataSource), null, null, null, null, null));
        assertThrows(SqlRewriteException.class, () -> unwrapKernel.batchExecute(upsertBatch));

        // 批量 UPDATE 被改写为 DELETE 时同样被 validateRewrittenShape 拦截
        JoyaClient updateToDeleteClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Update update) {
                        return new SqlNode.Delete(update.table(), update.where());
                    }
                    return node;
                })
                .build();
        assertThrows(SqlRewriteException.class,
                () -> updateToDeleteClient.of(PlainUser.class).updateAll(List.of(new PlainUser(10L, "u10"))));

        // 2. 批量 INSERT 改写器追加已存在的重复列名时，必须被 requireDistinctColumns 拒绝
        JoyaClient dupColBatchClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Insert insert) {
                        List<String> cols = new ArrayList<>(insert.columns());
                        cols.add("name");
                        List<List<SqlNode>> rows = new ArrayList<>();
                        for (List<SqlNode> row : insert.rows()) {
                            List<SqlNode> copy = new ArrayList<>(row);
                            copy.add(SqlNodes.param("dup_name", "dup", ParameterSource.RAW));
                            rows.add(copy);
                        }
                        return new SqlNode.Insert(insert.table(), cols, rows);
                    }
                    return node;
                })
                .build();
        assertThrows(SqlRewriteException.class,
                () -> dupColBatchClient.of(PlainUser.class)
                        .insertAll(List.of(new PlainUser(72L, "u72"))));

        // 3. supports(ctx) 返回 false 的改写器必须被跳过
        AtomicInteger skippedCalls = new AtomicInteger();
        SqlRewriter skippedRewriter = new SqlRewriter() {
            @Override
            public boolean supports(SqlRewriteContext context) {
                return false;
            }

            @Override
            public SqlNode rewrite(SqlNode node, SqlRewriteContext context) {
                skippedCalls.incrementAndGet();
                throw new AssertionError("supports() == false 的改写器不应被调用");
            }
        };
        JoyaClient filteredClient = JoyaClient.builder(dataSource).rewriter(skippedRewriter).build();
        assertEquals(3, filteredClient.from(AstUser.class).count());
        filteredClient.of(PlainUser.class).insertAll(List.of(new PlainUser(73L, "u73")));
        assertEquals(0, skippedCalls.get());
    }

    @Test
    void rewriterParameterMismatch_failsFastBeforeJdbc_andImplicitCountPageRewritesOnce() {
        // 1. 改写器产出 SqlNode.Raw 占位符与参数数量失配时，validateParameterContract 必须在发往 JDBC 前拦截
        JoyaClient mismatchClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Select select) {
                        SqlNode badRaw = SqlNodes.raw("1 = ? AND 2 = ?",
                                List.of(com.sondertara.joya.core.kernel.statement.SqlParameter.of(
                                        1, "onlyOne", 1, ParameterSource.RAW)));
                        return select.withWhere(SqlNodes.and(select.where(), badRaw));
                    }
                    return node;
                })
                .build();
        assertThrows(IllegalArgumentException.class, () -> mismatchClient.from(AstUser.class).list());

        // 2. 隐式计数分页对带 AST 的语句只执行一次改写，再派生 countSql 与 pageSql
        AtomicInteger rewriteCount = new AtomicInteger();
        JoyaClient pageClient = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    rewriteCount.incrementAndGet();
                    return new TenantRewriter("t1").rewrite(node, ctx);
                })
                .build();
        SqlStatement astSelect = pageClient.from(AstUser.class).toSqlStatement();
        com.sondertara.joya.core.query.PageResult<AstUser> page = pageClient.sql(astSelect)
                .page(AstUser.class, com.sondertara.joya.core.query.pagination.PageRequest.of(0, 10));
        assertEquals(2L, page.total());
        assertEquals(2, page.items().size());
        assertEquals(1, rewriteCount.get(), "隐式计数分页只应对基础 AST 语句触发一次 SqlRewriter");
    }

    /**
     * 批量 INSERT 租户改写器：在列尾追加 tenant_id，并给模板行追加常量参数。
     */
    private static final class InsertTenantRewriter implements SqlRewriter {
        private final String tenantId;

        private InsertTenantRewriter(String tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            if (!(node instanceof SqlNode.Insert insert)
                    || insert.columns().contains("tenant_id")) {
                return node;
            }
            List<String> columns = new ArrayList<>(insert.columns());
            columns.add("tenant_id");
            List<List<SqlNode>> rows = new ArrayList<>(insert.rows().size());
            for (List<SqlNode> row : insert.rows()) {
                List<SqlNode> copy = new ArrayList<>(row);
                copy.add(SqlNodes.param("tenant_id", tenantId, ParameterSource.TENANT));
                rows.add(copy);
            }
            return new SqlNode.Insert(insert.table(), columns, rows);
        }
    }

    /**
     * 测试用租户改写器：SELECT 使用 t0 别名；UPDATE/DELETE 为单表无别名。
     */
    private static final class TenantRewriter implements SqlRewriter {
        private final String tenantId;

        private TenantRewriter(String tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        public SqlNode rewrite(SqlNode node, SqlRewriteContext ctx) {
            if (node instanceof SqlNode.Select select) {
                SqlNode predicate = SqlNodes.eq(
                        SqlNodes.column(ctx.rootAlias(), "tenant_id"),
                        SqlNodes.param("tenantId", tenantId, ParameterSource.TENANT));
                return select.withWhere(SqlNodes.and(select.where(), predicate));
            }
            if (node instanceof SqlNode.Update update) {
                SqlNode predicate = SqlNodes.eq(
                        SqlNodes.column((SqlNode.Table) null, "tenant_id"),
                        SqlNodes.param("tenantId", tenantId, ParameterSource.TENANT));
                return update.withWhere(SqlNodes.and(update.where(), predicate));
            }
            if (node instanceof SqlNode.Delete delete) {
                SqlNode predicate = SqlNodes.eq(
                        SqlNodes.column((SqlNode.Table) null, "tenant_id"),
                        SqlNodes.param("tenantId", tenantId, ParameterSource.TENANT));
                return delete.withWhere(SqlNodes.and(delete.where(), predicate));
            }
            return node;
        }
    }
}
