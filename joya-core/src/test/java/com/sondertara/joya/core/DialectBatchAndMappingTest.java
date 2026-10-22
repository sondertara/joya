package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.kernel.dialect.DbType;
import com.sondertara.joya.core.jdbc.support.annotation.Query;
import com.sondertara.joya.core.kernel.PageSlice;
import com.sondertara.joya.core.kernel.dialect.Dialects;
import com.sondertara.joya.core.kernel.dialect.PaginationStyle;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterBinder;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.result.MutationResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言分页与标识符引用、批量写/更新分片与生成键回写、链式 Setter/无 Getter 字段映射专项测试。
 */
class DialectBatchAndMappingTest {

    /**
     * 链式 Setter + 无 Setter 字段 + 包级私有构造器的实体。
     */
    @Entity
    @Table(name = "t_chain_user")
    public static class ChainUser {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "user_name")
        private String userName;

        @Column(name = "dept_id")
        private Long deptId;

        // 故意不提供 getter/setter，验证预缓存 Field 读写回退
        @Column(name = "internal_note")
        String internalNote;

        ChainUser() {
        }

        public Long getId() {
            return id;
        }

        public ChainUser setId(Long id) {
            this.id = id;
            return this;
        }

        public String getUserName() {
            return userName;
        }

        public ChainUser setUserName(String userName) {
            this.userName = userName;
            return this;
        }

        public Long getDeptId() {
            return deptId;
        }

        public ChainUser setDeptId(Long deptId) {
            this.deptId = deptId;
            return this;
        }
    }

    @Entity
    @Table(name = "t_dept_ext")
    public static class DeptExt {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "dept_name")
        private String deptName;

        @Column(name = "active_flag")
        private Integer activeFlag;

        public DeptExt() {
        }

        public DeptExt(Long id, String deptName, Integer activeFlag) {
            this.id = id;
            this.deptName = deptName;
            this.activeFlag = activeFlag;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getDeptName() {
            return deptName;
        }

        public void setDeptName(String deptName) {
            this.deptName = deptName;
        }

        public Integer getActiveFlag() {
            return activeFlag;
        }

        public void setActiveFlag(Integer activeFlag) {
            this.activeFlag = activeFlag;
        }
    }

    @Entity
    @Table(name = "t_audit", schema = "sec_schema")
    public static class SchemaScopedAudit {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "action_name")
        private String actionName;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getActionName() {
            return actionName;
        }

        public void setActionName(String actionName) {
            this.actionName = actionName;
        }
    }

    @Entity
    @Table(name = "t_auto_item")
    public static class AutoKeyItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "title")
        private String title;

        @Column(name = "status")
        private String status;

        @Column(name = "qty")
        private Integer qty;

        public AutoKeyItem() {
        }

        public AutoKeyItem(Long id, String title, String status, Integer qty) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.qty = qty;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Integer getQty() {
            return qty;
        }

        public void setQty(Integer qty) {
            this.qty = qty;
        }
    }

    public record UserDeptJoinView(String userName, String deptName) {
    }

    public interface ChainUserMapper extends JoyaMapper<ChainUser> {

        @Query("SELECT * FROM t_chain_user WHERE id IN (?1) AND dept_id = ?2 ORDER BY id ASC")
        List<ChainUser> findByIdsAndDept(List<Long> ids, Long deptId);

        @Query("/*+ INDEX(t_chain_user) */ UPDATE t_chain_user SET user_name = ?1 WHERE id = ?2")
        int updateNameWithLeadingHint(String newName, Long id);

        @Query("-- line comment\nDELETE FROM t_chain_user WHERE id = ?1")
        int deleteWithLineComment(Long id);

        PageResult<ChainUser> findByDeptIdOrderByIdAsc(Long deptId, PageRequest page);

        List<ChainUser> findByDeptIdOrderByIdDesc(Long deptId, PageRequest page);
    }

    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dialect_batch_mapping;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        joya = JoyaClient.builder(ds).build();
        joya.sql("DROP TABLE IF EXISTS t_chain_user").update();
        joya.sql("DROP TABLE IF EXISTS t_dept_ext").update();
        joya.sql("DROP TABLE IF EXISTS t_auto_item").update();
        joya.sql("""
                CREATE TABLE t_auto_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(64) NOT NULL,
                    status VARCHAR(32) DEFAULT 'PENDING',
                    qty INT DEFAULT 0
                )
                """).update();
        joya.sql("""
                CREATE TABLE t_dept_ext (
                    id BIGINT PRIMARY KEY,
                    dept_name VARCHAR(64) NOT NULL,
                    active_flag INT NOT NULL
                )
                """).update();
        joya.sql("""
                CREATE TABLE t_chain_user (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64) NOT NULL,
                    dept_id BIGINT,
                    internal_note VARCHAR(128)
                )
                """).update();

        joya.sql("INSERT INTO t_dept_ext (id, dept_name, active_flag) VALUES (?, ?, ?)", 10L, "R&D", 1).update();
        joya.sql("INSERT INTO t_dept_ext (id, dept_name, active_flag) VALUES (?, ?, ?)", 20L, "LegacyDept", 0).update();

        joya.sql("INSERT INTO t_chain_user (id, user_name, dept_id, internal_note) VALUES (?, ?, ?, ?)",
                1L, "Alice", 10L, "note-a").update();
        joya.sql("INSERT INTO t_chain_user (id, user_name, dept_id, internal_note) VALUES (?, ?, ?, ?)",
                2L, "Bob", 20L, "note-b").update();
        joya.sql("INSERT INTO t_chain_user (id, user_name, dept_id, internal_note) VALUES (?, ?, ?, ?)",
                3L, "Charlie", 10L, "note-c").update();
    }

    @Test
    void beanRowMapperShouldPopulateChainedSettersAndFieldOnlyProperties() {
        ChainUser loaded = joya.of(ChainUser.class).findById(1L).orElseThrow();
        assertEquals(1L, loaded.getId());
        assertEquals("Alice", loaded.getUserName());
        assertEquals(10L, loaded.getDeptId());
        assertEquals("note-a", loaded.internalNote);
    }

    @Test
    void leftJoinWithExtraOnConditionAndSchemaQualifiedTableNameShouldRenderCorrectly() {
        List<UserDeptJoinView> rows = joya.from(ChainUser.class)
                .leftJoin(ChainUser::getDeptId, DeptExt::getId, on -> on.eq(DeptExt::getActiveFlag, 1))
                .where(w -> w.in(ChainUser::getId, List.of(1L, 2L)))
                .orderByAsc(ChainUser::getId)
                .select(ChainUser::getUserName, DeptExt::getDeptName)
                .list(UserDeptJoinView::new);

        assertEquals(2, rows.size());
        assertEquals(new UserDeptJoinView("Alice", "R&D"), rows.get(0));
        assertEquals("Bob", rows.get(1).userName());
        assertNull(rows.get(1).deptName());

        SqlStatement schemaStmt = com.sondertara.joya.core.query.dsl.Query.of(SchemaScopedAudit.class)
                .where(w -> w.eq(SchemaScopedAudit::getActionName, "LOGIN"))
                .toSqlStatement();
        assertTrue(schemaStmt.sql().contains("FROM sec_schema.t_audit t0"), "Actual SQL: " + schemaStmt.sql());
        assertTrue(schemaStmt.metadata().tables().stream()
                .anyMatch(t -> "sec_schema.t_audit".equals(t.name())));
    }

    @Test
    void templatePaginationShouldPreserveCustomParameterBinderAndSqlServerPlaceholders() {
        AtomicBoolean customBinderInvoked = new AtomicBoolean(false);
        ParameterBinder customBinder = (ps, index, param) -> {
            customBinderInvoked.set(true);
            ps.setLong(index, (Long) param.value());
        };
        SqlParameter customParam = new SqlParameter(1, "dept_id", 10L, ParameterSource.WHERE, customBinder);
        StatementMetadata meta = StatementMetadata.builder(Operation.SELECT)
                .table(TableRef.of("t_chain_user"))
                .entityType(ChainUser.class)
                .build();
        SqlStatement stmt = SqlStatement.of(
                "SELECT id, user_name, dept_id, internal_note FROM t_chain_user WHERE dept_id = ? ORDER BY id ASC",
                List.of(customParam),
                meta);

        PageSlice<ChainUser> page = joya.template().queryPage(
                stmt,
                com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper.of(ChainUser.class),
                0L,
                1L);
        assertTrue(customBinderInvoked.get(), "Custom ParameterBinder must be preserved across queryPage/queryLimit");
        assertEquals(2L, page.total());
        assertEquals(1, page.rows().size());
        assertEquals("Alice", page.rows().get(0).getUserName());

        SqlStatement baseStmt = SqlStatement.select("SELECT id FROM t_chain_user WHERE dept_id = ? ORDER BY id ASC", 10L);

        SqlStatement sqlServer2012Page = Dialects.SQLSERVER.pageSql(baseStmt, 20, 10);
        assertTrue(sqlServer2012Page.sql().endsWith("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        assertEquals(List.of(10L, 20L, 10L), sqlServer2012Page.args());

        SqlStatement sqlServer2005Page = Dialects.SQLSERVER_2005.pageSql(baseStmt, 20, 10);
        long qmCount = sqlServer2005Page.sql().chars().filter(c -> c == '?').count();
        assertEquals(sqlServer2005Page.parameters().size(), qmCount);
        assertEquals(List.of(10L, 21L, 30L), sqlServer2005Page.args());

        SqlStatement db2Page = Dialects.DB2.pageSql(baseStmt, 20, 10);
        assertEquals(List.of(10L, 21L, 30L), db2Page.args(), "DB2 BETWEEN must use 1-based [offset+1, offset+limit]");

        SqlStatement informixPage = Dialects.INFORMIX.pageSql(baseStmt, 5, 10);
        assertEquals(List.of(5L, 10L, 10L), informixPage.args());

        SqlStatement oracleCount = Dialects.ORACLE.countSql(baseStmt);
        assertEquals("SELECT COUNT(*) FROM (SELECT id FROM t_chain_user WHERE dept_id = ? ORDER BY id ASC) TEMP_T", oracleCount.sql());

        assertEquals("`t_order`", Dialects.MYSQL.quoteIdentifier("t_order"));
        assertEquals("\"sec\".\"t_order\"", Dialects.POSTGRESQL.quoteIdentifier("sec.t_order"));
        assertEquals("[dbo].[t_order]", Dialects.SQLSERVER.quoteIdentifier("dbo.t_order"));
        assertEquals(QuoteStyle.DOUBLE_QUOTE, Dialects.H2.quoteStyle());
        assertEquals(PaginationStyle.LIMIT_OFFSET, Dialects.H2.paginationStyle());

        assertEquals(DbType.sqlserver, DbType.of("Microsoft SQL Server"));
        assertEquals(DbType.derby, DbType.of("Apache Derby"));
        assertEquals(DbType.hsql, DbType.of("HSQL Database Engine"));
        assertEquals(DbType.db2, DbType.of("DB2/LINUXX8664"));
        assertEquals(DbType.dm, DbType.of("DM DBMS"));
        assertEquals(DbType.kingbase, DbType.of("KingbaseES"));
    }

    @Test
    void mapperShouldSupportNativeInExpansionLeadingCommentUpdateAndPageRequest() {
        ChainUserMapper mapper = joya.mapper(ChainUserMapper.class);

        List<ChainUser> inResult = mapper.findByIdsAndDept(List.of(1L, 3L), 10L);
        assertEquals(2, inResult.size());
        assertEquals("Alice", inResult.get(0).getUserName());
        assertEquals("Charlie", inResult.get(1).getUserName());

        PageResult<ChainUser> paged = mapper.findByDeptIdOrderByIdAsc(10L, PageRequest.of(0, 1));
        assertEquals(2L, paged.getTotal());
        assertEquals(1, paged.getData().size());
        assertEquals("Alice", paged.getData().get(0).getUserName());

        List<ChainUser> slice = mapper.findByDeptIdOrderByIdDesc(10L, PageRequest.of(0, 1));
        assertEquals(1, slice.size());
        assertEquals("Charlie", slice.get(0).getUserName());

        int updated = mapper.updateNameWithLeadingHint("AliceRenamed", 1L);
        assertEquals(1, updated);
        assertEquals("AliceRenamed", joya.of(ChainUser.class).findById(1L).orElseThrow().getUserName());

        int deleted = mapper.deleteWithLineComment(2L);
        assertEquals(1, deleted);
        assertTrue(joya.of(ChainUser.class).findById(2L).isEmpty());
    }

    @Test
    void defaultParameterBinderShouldBindNullViaSetNullInsteadOfSetObjectNull() throws Exception {
        AtomicInteger setNullSqlType = new AtomicInteger(Integer.MIN_VALUE);
        PreparedStatement proxyPs = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setNull".equals(method.getName()) && args != null && args.length == 2) {
                        setNullSqlType.set((Integer) args[1]);
                        return null;
                    }
                    if ("setObject".equals(method.getName()) && args != null && args.length == 2 && args[1] == null) {
                        throw new AssertionError("setObject(index, null) should not be called when setNull succeeds");
                    }
                    return null;
                });

        SqlParameter nullParam = SqlParameter.of(1, "col", null, ParameterSource.SET);
        ParameterBinder.DEFAULT.bind(proxyPs, 1, nullParam);
        assertEquals(Types.NULL, setNullSqlType.get());
        assertNotNull(nullParam);
    }

    @Test
    void batchOperationsShouldSupportKeyWriteBackSelectiveUpdatePatchIgnoreNullAndChunking() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dialect_batch_mapping;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        JoyaClient chunkedJoya = JoyaClient.builder(ds)
                .settings(JoyaSettings.builder().batchSize(2).build())
                .build();
        EntityOps<AutoKeyItem> items = chunkedJoya.of(AutoKeyItem.class);

        AutoKeyItem i1 = new AutoKeyItem(null, "Item-1", null, 10);
        AutoKeyItem i2 = new AutoKeyItem(null, "Item-2", null, 20);
        AutoKeyItem i3 = new AutoKeyItem(null, "Item-3", "ACTIVE", null);
        MutationResult insRes = items.insertAllIgnoreNull(List.of(i1, i2, i3));

        assertEquals(3L, insRes.affectedRows());
        assertEquals(3, insRes.batchCounts().length);
        assertNotNull(i1.getId());
        assertNotNull(i2.getId());
        assertNotNull(i3.getId());
        assertEquals(3, insRes.getGeneratedKeys(Long.class).size());

        AutoKeyItem loaded1 = items.findById(i1.getId()).orElseThrow();
        assertEquals("PENDING", loaded1.getStatus());
        assertEquals(10, loaded1.getQty());

        AutoKeyItem loaded3 = items.findById(i3.getId()).orElseThrow();
        assertEquals("ACTIVE", loaded3.getStatus());
        assertEquals(0, loaded3.getQty());

        AutoKeyItem sel1 = new AutoKeyItem(i1.getId(), null, null, 100);
        AutoKeyItem sel2 = new AutoKeyItem(i2.getId(), null, null, 200);
        MutationResult selRes = items.updateAll(List.of(sel1, sel2), AutoKeyItem::getQty);
        assertEquals(2L, selRes.affectedRows());
        assertEquals("Item-1", items.findById(i1.getId()).orElseThrow().getTitle());
        assertEquals("PENDING", items.findById(i1.getId()).orElseThrow().getStatus());
        assertEquals(100, items.findById(i1.getId()).orElseThrow().getQty());
        assertEquals(200, items.findById(i2.getId()).orElseThrow().getQty());

        AutoKeyItem patch1 = new AutoKeyItem(i1.getId(), null, "SHIPPED", null);
        AutoKeyItem patch2 = new AutoKeyItem(i2.getId(), "Item-2-Renamed", null, 250);
        MutationResult patchRes = items.patchAll(List.of(patch1, patch2));
        assertEquals(2L, patchRes.affectedRows());
        assertEquals("SHIPPED", items.findById(i1.getId()).orElseThrow().getStatus());
        assertEquals(100, items.findById(i1.getId()).orElseThrow().getQty());
        assertEquals("Item-2-Renamed", items.findById(i2.getId()).orElseThrow().getTitle());
        assertEquals(250, items.findById(i2.getId()).orElseThrow().getQty());

        AutoKeyItem existingUpdate = new AutoKeyItem(i3.getId(), "Item-3-Updated", "DONE", 300);
        AutoKeyItem brandNew = new AutoKeyItem(null, "Item-4-New", "NEW", 400);
        MutationResult saveAllRes = items.saveAll(List.of(existingUpdate, brandNew));
        assertEquals(2L, saveAllRes.affectedRows());
        assertNotNull(brandNew.getId());
        assertEquals("DONE", items.findById(i3.getId()).orElseThrow().getStatus());
        assertEquals("Item-4-New", items.findById(brandNew.getId()).orElseThrow().getTitle());

        int[] rawBatchCounts = chunkedJoya.sql("UPDATE t_auto_item SET qty = qty + ? WHERE id = ?")
                .batch(List.of(i1, i2, i3), 2, item -> new Object[]{5, item.getId()});
        assertArrayEquals(new int[]{1, 1, 1}, rawBatchCounts);
        assertEquals(105, items.findById(i1.getId()).orElseThrow().getQty());

        MutationResult noInfoBatch = MutationResult.ofBatch(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO, 1});
        assertEquals(3L, noInfoBatch.affectedRows());
    }
}
