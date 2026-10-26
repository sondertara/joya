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

        @Query("""
                /* leading ?99 ' */
                SELECT * FROM t_chain_user
                WHERE id IN (?1)
                  AND `dept_id` = ?2
                  AND user_name <> 'it''s ?98'
                  -- trailing ?97 "
                ORDER BY id ASC
                """)
        List<ChainUser> findByArrayIdsAndDeptWithLexicalNoise(long[] ids, Long deptId);

        @Query("# hash ?96 '\nSELECT * FROM t_chain_user WHERE id = ?1 AND dept_id = ?2")
        List<ChainUser> findWithHashComment(Long id, Long deptId);

        @Query("/*+ INDEX(t_chain_user) */ UPDATE t_chain_user SET user_name = ?1 WHERE id = ?2")
        int updateNameWithLeadingHint(String newName, Long id);

        @Query("-- line comment\nDELETE FROM t_chain_user WHERE id = ?1")
        int deleteWithLineComment(Long id);

        @Query("/* ?99 */ SELECT * FROM t_chain_user WHERE user_name <> '?88' AND id = ?1 -- ?77")
        List<ChainUser> findWithQuestionMarksInCommentsAndLiterals(Long id);

        @Query("# leading hash comment\nUPDATE t_chain_user SET user_name = 'a\\' b', dept_id = ?2 WHERE id = ?1")
        int updateWithMysqlHashCommentAndBackslashQuote(Long id, Long deptId);

        @Query("SELECT * FROM t_chain_user WHERE id = ?0")
        List<ChainUser> findWithZeroIndexedPlaceholder(Long id);

        PageResult<ChainUser> findByDeptIdOrderByIdAsc(Long deptId, PageRequest page);

        List<ChainUser> findByDeptIdOrderByIdDesc(Long deptId, PageRequest page);
    }

    @Entity
    @Table(name = "t_chain_user")
    public static class MapperAuditedUser implements com.sondertara.joya.core.jdbc.support.listener.EntityListener {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "user_name")
        private String userName;

        @Column(name = "dept_id")
        private Long deptId;

        private transient int preUpdateCount;
        private transient int postUpdateCount;

        public MapperAuditedUser() {
        }

        public MapperAuditedUser(Long id, String userName, Long deptId) {
            this.id = id;
            this.userName = userName;
            this.deptId = deptId;
        }

        @Override
        public void preUpdate(Object entity) {
            preUpdateCount++;
        }

        @Override
        public void postUpdate(Object entity) {
            postUpdateCount++;
        }
    }

    public interface MapperAuditedUserMapper extends JoyaMapper<MapperAuditedUser> {
        int updateById(MapperAuditedUser entity, Long id);
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
                .anyMatch(t -> "t_audit".equals(t.name())
                        && "sec_schema".equals(t.schema())
                        && "sec_schema.t_audit".equals(t.qualifiedName())));
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

        List<ChainUser> beyondLastPage = mapper.findByDeptIdOrderByIdDesc(
                10L, PageRequest.of(Integer.MAX_VALUE, 2));
        assertTrue(beyondLastPage.isEmpty(), "Mapper 列表分页应使用 long OFFSET，而不是在 int 乘法处溢出");

        int updated = mapper.updateNameWithLeadingHint("AliceRenamed", 1L);
        assertEquals(1, updated);
        assertEquals("AliceRenamed", joya.of(ChainUser.class).findById(1L).orElseThrow().getUserName());

        int deleted = mapper.deleteWithLineComment(2L);
        assertEquals(1, deleted);
        assertTrue(joya.of(ChainUser.class).findById(2L).isEmpty());
    }

    @Test
    void sqlPlaceholderScanningShouldIgnoreQuotedAndCommentedQuestionMarks() {
        String sql = """
                SELECT `c?9`, "d?8", 'it''s ?7'
                /* ?6 ' */ -- ?5 "
                # ?4 '
                FROM t WHERE a = ?1 AND b = ?2
                  AND note = $$ ?3 ' -- /* $$
                  AND other = $tag$?4 `` $tag$
                """;
        String expected = sql.replace("a = ?1", "a = ?").replace("b = ?2", "b = ?");
        assertEquals(expected, com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(sql));
        assertEquals(2, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(sql));
        assertEquals(
                List.of(1, 2),
                com.sondertara.joya.utils.SqlFormatUtils.scanPlaceholders(sql).stream()
                        .map(com.sondertara.joya.utils.SqlFormatUtils.Placeholder::index)
                        .toList());

        String escaped = "SELECT 'a\\'?1' AS s, \"a\\\"?2\" AS d, `a\\`?3` AS b WHERE id = ?1";
        assertEquals(1, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(escaped));
        assertEquals(
                escaped.substring(0, escaped.length() - 1),
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(escaped));

        String standardBackslash = "SELECT * FROM t WHERE name LIKE ? ESCAPE '\\' OR age BETWEEN ? AND ?";
        assertEquals(3, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(standardBackslash));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> com.sondertara.joya.utils.SqlFormatUtils.scanPlaceholders("SELECT $$ ?1"));
    }

    @Test
    void mapperShouldUseConsistentLexerForNativeParametersAndArrayExpansion() throws Exception {
        ChainUserMapper mapper = joya.mapper(ChainUserMapper.class);
        List<ChainUser> rows = mapper.findByArrayIdsAndDeptWithLexicalNoise(new long[]{1L, 3L}, 10L);
        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).getUserName());
        assertEquals("Charlie", rows.get(1).getUserName());

        java.lang.reflect.Method hashMethod = ChainUserMapper.class.getMethod(
                "findWithHashComment", Long.class, Long.class);
        com.sondertara.joya.core.jdbc.dao.support.EntityBasedBoundSql parsed =
                new com.sondertara.joya.core.jdbc.dao.support.NativeQueryParser(ChainUser.class, hashMethod).parse();
        assertEquals(2, parsed.parameterBinding().size());
        String normalized = com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(parsed.getSqlString());
        assertEquals(2, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(normalized));
        assertTrue(normalized.contains("WHERE id = ? AND dept_id = ?"), normalized);
    }

    @Test
    void mapperEntityUpdateShouldFirePostUpdateOnlyAfterSuccessfulUpdate() {
        MapperAuditedUserMapper mapper = joya.mapper(MapperAuditedUserMapper.class);
        MapperAuditedUser success = new MapperAuditedUser(1L, "AliceAfterDerivedUpdate", 10L);
        assertEquals(1, mapper.updateById(success, 1L));
        assertEquals(1, success.preUpdateCount);
        assertEquals(1, success.postUpdateCount);
        assertEquals(
                "AliceAfterDerivedUpdate",
                joya.of(ChainUser.class).findById(1L).orElseThrow().getUserName());

        MapperAuditedUser failure = new MapperAuditedUser(2L, null, 20L);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sondertara.joya.exceptions.DbException.class,
                () -> mapper.updateById(failure, 2L));
        assertEquals(1, failure.preUpdateCount);
        assertEquals(0, failure.postUpdateCount);
        assertEquals(1, success.postUpdateCount, "不可缓存的实体更新绑定不得复用其他实体的回调");
        assertEquals("Bob", joya.of(ChainUser.class).findById(2L).orElseThrow().getUserName());
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

    @Entity
    @Table(name = "t_p0_meta")
    public static class P0MetaEntity {
        @Id
        @Column(name = "id")
        private String id;

        @Column(name = "title")
        @com.fasterxml.jackson.annotation.JsonProperty("json_title")
        private String title;

        @Column(name = "readonly_col", insertable = false, updatable = false)
        private String readonlyCol;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getReadonlyCol() {
            return readonlyCol;
        }

        public void setReadonlyCol(String readonlyCol) {
            this.readonlyCol = readonlyCol;
        }
    }

    @Entity
    @Table(name = "t_bad_primitive_pk")
    public static class BadPrimitivePkEntity {
        @Id
        private long id;
    }

    @Test
    void p0FixesShouldPreventStreamConnectionLeakPreserveTxInScriptAndHonorColumnMetaAndDateMapping() {
        // 1. queryStream 在参数绑定抛出 RuntimeException 时必须归还连接
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dialect_batch_mapping;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");

        AtomicBoolean releasedOnRuntimeError = new AtomicBoolean(false);
        com.sondertara.joya.core.kernel.connection.ConnectionProvider trackingProvider =
                new com.sondertara.joya.core.kernel.connection.ConnectionProvider() {
                    @Override
                    public java.sql.Connection acquire() throws java.sql.SQLException {
                        return ds.getConnection();
                    }

                    @Override
                    public void release(java.sql.Connection connection) {
                        releasedOnRuntimeError.set(true);
                        try {
                            connection.close();
                        } catch (java.sql.SQLException ignored) {
                        }
                    }
                };
        com.sondertara.joya.core.kernel.executor.JdbcExecutor customExec =
                new com.sondertara.joya.core.kernel.executor.JdbcExecutor(trackingProvider);
        ParameterBinder failingBinder = (ps, index, param) -> {
            throw new IllegalStateException("Simulated runtime failure in binder");
        };
        SqlStatement failingStmt = SqlStatement.of(
                "SELECT id FROM t_chain_user WHERE id = ?",
                List.of(new SqlParameter(1, "id", 1L, ParameterSource.WHERE, failingBinder)),
                StatementMetadata.builder(Operation.SELECT).build());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> customExec.queryStream(failingStmt, rs -> 1L));
        assertTrue(releasedOnRuntimeError.get(), "Connection must be released when queryStream throws RuntimeException");

        // 2. ScriptRunner 在显式事务连接（autoCommit=false）下执行不得无条件回滚外层已执行的写操作
        joya.withConnection(conn -> {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("UPDATE t_chain_user SET internal_note = 'BeforeScript' WHERE id = 1");
                }
                com.sondertara.joya.core.kernel.support.ScriptRunner runner =
                        new com.sondertara.joya.core.kernel.support.ScriptRunner(conn);
                runner.runScript(new java.io.StringReader("UPDATE t_chain_user SET internal_note = 'AfterScript' WHERE id = 3;"));
                conn.commit();
            } finally {
                conn.setAutoCommit(prevAuto);
            }
            return null;
        });
        assertEquals("BeforeScript", joya.of(ChainUser.class).findById(1L).orElseThrow().internalNote);
        assertEquals("AfterScript", joya.of(ChainUser.class).findById(3L).orElseThrow().internalNote);

        // 3. BeanRowMapper 单列查询支持 Timestamp / Time / java.util.Date（不抛 ClassCastException 且保留时分秒）
        java.sql.Timestamp ts = joya.template().queryOne(
                SqlStatement.select("SELECT TIMESTAMP '2026-05-01 14:25:36'"),
                com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper.of(java.sql.Timestamp.class));
        assertEquals(java.sql.Timestamp.valueOf("2026-05-01 14:25:36"), ts);

        java.sql.Time sqlTime = joya.template().queryOne(
                SqlStatement.select("SELECT TIME '14:25:36'"),
                com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper.of(java.sql.Time.class));
        assertEquals(java.sql.Time.valueOf("14:25:36"), sqlTime);

        java.util.Date utilDate = joya.template().queryOne(
                SqlStatement.select("SELECT TIMESTAMP '2026-05-01 14:25:36'"),
                com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper.of(java.util.Date.class));
        assertNotNull(utilDate);
        assertEquals(java.sql.Timestamp.valueOf("2026-05-01 14:25:36").getTime(), utilDate.getTime());

        // 4. @Column(insertable=false, updatable=false) 与非自增手工主键在 InsertStatementFactory.create(ctx) 下正确包含主键并排除只读列
        com.sondertara.joya.core.metadata.EntityMetadataContext p0Meta =
                com.sondertara.joya.core.cache.CacheHolder.tableMetaCache().getMetadataContext(P0MetaEntity.class);
        assertEquals(List.of("id", "title"), p0Meta.insertableColumns());
        assertEquals(List.of("id", "title"), p0Meta.insertableColumnsWithPrimaryKey());
        assertEquals(List.of("title"), p0Meta.updatableColumns());

        P0MetaEntity entity = new P0MetaEntity();
        entity.setId("manual-id-01");
        entity.setTitle("Hello");
        entity.setReadonlyCol("ShouldBeIgnored");
        com.sondertara.joya.core.statement.MutationContext mutCtx =
                new com.sondertara.joya.core.statement.MutationContext(
                        p0Meta,
                        com.sondertara.joya.core.metadata.EntitySnapshot.snapshot(p0Meta, entity).values(),
                        null,
                        java.util.Set.of(),
                        false,
                        false,
                        com.sondertara.joya.core.naming.NamingPolicy.DEFAULT);
        SqlStatement insStmt = com.sondertara.joya.core.statement.factory.InsertStatementFactory.create(mutCtx);
        assertEquals("INSERT INTO t_p0_meta (id, title) VALUES (?, ?)", insStmt.sql());
        assertEquals(List.of("manual-id-01", "Hello"), insStmt.args());

        // 5. 基本类型主键拒绝
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sondertara.joya.exceptions.DbException.class,
                () -> com.sondertara.joya.core.cache.CacheHolder.tableMetaCache().getMetadataContext(BadPrimitivePkEntity.class));
    }

    enum SampleStatus {
        ACTIVE,
        SUSPENDED
    }

    @Entity
    @Table(name = "t_emp_self")
    public static class EmpSelf {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "manager_id")
        private Long managerId;

        @Column(name = "emp_name")
        private String empName;

        public Long getId() {
            return id;
        }

        public Long getManagerId() {
            return managerId;
        }

        public String getEmpName() {
            return empName;
        }
    }

    @Test
    void p1FixesShouldSupportDeadlockRetryEnumBindingSqlServerOrderByLikeEscapeSelfJoinAndQuoteStyle() throws Exception {
        // 1. TxExecutor 死锁重试（SQLState 40001）+ 指数退避
        AtomicInteger attempts = new AtomicInteger(0);
        long startNs = System.nanoTime();
        String retryResult = joya.tx()
                .retryOnDeadlock(3)
                .call(tx -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new com.sondertara.joya.exceptions.DbException(
                                new java.sql.SQLException("Deadlock detected", "40001"));
                    }
                    return "OK_AFTER_RETRY";
                });
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertEquals("OK_AFTER_RETRY", retryResult);
        assertEquals(3, attempts.get());
        assertTrue(elapsedMs >= 20L, "Deadlock retry must apply exponential backoff delay");

        // 2. ParameterBinder 支持 Enum 绑定为字符串名称
        java.util.concurrent.atomic.AtomicReference<String> boundEnum = new java.util.concurrent.atomic.AtomicReference<>();
        PreparedStatement enumPs = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setString".equals(method.getName()) && args != null && args.length == 2) {
                        boundEnum.set((String) args[1]);
                    }
                    return null;
                });
        ParameterBinder.DEFAULT.bindValue(enumPs, 1, SampleStatus.SUSPENDED);
        assertEquals("SUSPENDED", boundEnum.get());

        // 3. PaginationStyle.SQLSERVER_ROW_NUMBER 剥离内层顶层 ORDER BY 且忽略子查询内 ORDER BY
        SqlStatement subOrderStmt = SqlStatement.select(
                "SELECT id, (SELECT COUNT(*) FROM t_sub ORDER BY sub_id) AS cnt FROM t_chain_user WHERE dept_id = ? ORDER BY id DESC",
                10L);
        SqlStatement ss2005 = Dialects.SQLSERVER_2005.pageSql(subOrderStmt, 0, 5);
        assertTrue(ss2005.sql().contains("ROW_NUMBER() OVER (ORDER BY id DESC)"));
        assertTrue(ss2005.sql().contains("WHERE dept_id = ?) SELECT * FROM selectTemp"),
                "Top-level ORDER BY must be stripped from inner CTE body: " + ss2005.sql());

        // 4. WhereCriterion LIKE 通配符安全转义（% / _ / \）并附加 ESCAPE '\'
        SqlStatement likeStmt = com.sondertara.joya.core.query.dsl.Query.of(ChainUser.class)
                .where(w -> w.contains(ChainUser::getUserName, "50%_off\\deal"))
                .toSqlStatement();
        assertTrue(likeStmt.sql().contains("LIKE ? ESCAPE '\\'"), "Escaped LIKE must include ESCAPE clause: " + likeStmt.sql());
        assertEquals(List.of("%50\\%\\_off\\\\deal%"), likeStmt.args());

        // 5. RenderContext 支持同表自关联（Self-Join）分配独立别名 t0 / t1
        SqlStatement selfJoinStmt = com.sondertara.joya.core.query.dsl.Query.of(EmpSelf.class)
                .leftJoin(EmpSelf::getManagerId, EmpSelf::getId, on -> on.eq("t1.empName", "Boss"))
                .toSqlStatement();
        assertTrue(selfJoinStmt.sql().contains("FROM t_emp_self t0 LEFT JOIN t_emp_self t1 ON t0.manager_id = t1.id AND t1.emp_name = ?"),
                "Self-join must allocate distinct alias t1: " + selfJoinStmt.sql());

        // 6. NamingPolicy / JoyaSettings QuoteStyle 接通渲染层
        JoyaSettings quotedSettings = JoyaSettings.builder()
                .quoteStyle(QuoteStyle.BACKTICK)
                .build();
        SqlStatement quotedSelect = com.sondertara.joya.core.statement.factory.SelectStatementFactory.create(
                com.sondertara.joya.core.cache.CacheHolder.tableMetaCache().getMetadataContext(SchemaScopedAudit.class),
                com.sondertara.joya.core.query.criterion.WhereCriterion.create().eq(SchemaScopedAudit::getId, 1L),
                null,
                null,
                quotedSettings.namingPolicy());
        assertEquals("SELECT * FROM `sec_schema`.`t_audit` WHERE `id` = ?", quotedSelect.sql());

        // 7. UpsertPlanner.saveAll 批次内重复主键显式报错，拒绝静默覆盖
        ChainUser dup1 = new ChainUser();
        dup1.setId(777L);
        dup1.setUserName("first");
        ChainUser dup2 = new ChainUser();
        dup2.setId(777L);
        dup2.setUserName("second");
        IllegalArgumentException dupEx = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.of(ChainUser.class).saveAll(List.of(dup1, dup2)));
        assertTrue(dupEx.getMessage().contains("Duplicate primary key [777]"));

        // 8. 方言感知流式 fetchSize（MySQL 系 Integer.MIN_VALUE vs 默认 2000）
        assertEquals(Integer.MIN_VALUE, Dialects.MYSQL.streamFetchSize());
        assertEquals(Integer.MIN_VALUE, Dialects.MARIADB.streamFetchSize());
        assertEquals(2000, Dialects.POSTGRESQL.streamFetchSize());
        assertEquals(2000, Dialects.H2.streamFetchSize());

        // 9. RawColumnParser 含空格函数与多列引用解析（不破坏括号内逗号与空格）
        com.sondertara.joya.core.query.ColumnPair fnPair =
                com.sondertara.joya.core.query.RawColumnParser.parse("COALESCE(t0.userName, t0.internalNote) AS display_label");
        assertEquals("COALESCE(t0.user_name, t0.internal_note)", fnPair.getKey());
        assertEquals("display_label", fnPair.getValue());

        // 10. SqlFormatUtils.getJdbcStyleSql 跳过单引号字符串字面量内的 ?1
        assertEquals(
                "SELECT * FROM t_user WHERE note = '?1' AND id = ? AND status = ?",
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(
                        "SELECT * FROM t_user WHERE note = '?1' AND id = ?1 AND status = ?2"));

        // 11. Query.limit(n).count() 与 limit(offset, count).count() 遵循 limit / offset 约束
        long fullCount = joya.from(ChainUser.class).count();
        assertTrue(fullCount >= 3L);
        assertEquals(2L, joya.from(ChainUser.class).limit(2).count());
        assertEquals(0L, joya.from(ChainUser.class).limit((int) fullCount + 10, 2).count());
        assertEquals(false, joya.from(ChainUser.class).limit((int) fullCount + 10, 2).exists());
    }

    @Entity
    @Table(name = "t_dual_id")
    public static class DualIdEntity {
        @Id
        private Long id1;
        @Id
        private Long id2;
    }

    @Entity
    @Table(name = "t_chain_user")
    public static class DefaultedChainUser {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "user_name")
        private String userName = "DEFAULT_NAME";

        @Column(name = "internal_note")
        private String internalNote = "NON_NULL_DEFAULT";

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getInternalNote() {
            return internalNote;
        }

        public void setInternalNote(String internalNote) {
            this.internalNote = internalNote;
        }
    }

    @Test
    void auditRound2Fixes_coverCompositePkNullBeanOverwriteBigDecimalLocalTimePaginationAndJoinGuards() throws Exception {
        // 1. 复合主键实体快速失败
        org.junit.jupiter.api.Assertions.assertThrows(
                com.sondertara.joya.exceptions.DbException.class,
                () -> com.sondertara.joya.core.cache.CacheHolder.tableMetaCache().getMetadataContext(DualIdEntity.class));

        // 2. @Column(insertable=false, updatable=false) 在流式 EntityInsert / EntityUpdate 中同样强制拦截
        P0MetaEntity ro = new P0MetaEntity();
        ro.setId("id-1");
        ro.setTitle("t");
        ro.setReadonlyCol("ro");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.of(P0MetaEntity.class).insert().value(P0MetaEntity::getReadonlyCol, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.of(P0MetaEntity.class).update().set(P0MetaEntity::getReadonlyCol, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.of(P0MetaEntity.class).updateAll(List.of(ro), P0MetaEntity::getReadonlyCol));

        // 3. BeanRowMapper 数据库 NULL 覆盖字段非空默认值
        joya.sql("UPDATE t_chain_user SET internal_note = NULL WHERE id = 1").update();
        DefaultedChainUser mappedWithNull = joya.of(DefaultedChainUser.class).findById(1L).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(
                mappedWithNull.getInternalNote(),
                "Database NULL must overwrite non-null Java field default value");

        // 4. SqlDataUtils Number.class 保持高精度 BigDecimal & LocalTime 保持纳秒精度
        Number preciseNum = joya.sql("SELECT CAST('9007199254740993.123456' AS DECIMAL(30, 6))")
                .value(Number.class)
                .orElseThrow();
        assertEquals(new java.math.BigDecimal("9007199254740993.123456"), preciseNum);

        java.time.LocalTime preciseTime = joya.sql("SELECT TIME '12:34:56.789123'")
                .value(java.time.LocalTime.class)
                .orElseThrow();
        assertEquals(java.time.LocalTime.of(12, 34, 56, 789_123_000), preciseTime);

        // 5. SqlOps.insert 真实返回受影响行数（0 行 vs 1 行）
        var zeroInsert = joya.sql(
                "INSERT INTO t_chain_user (id, user_name, dept_id) SELECT 999, 'Ghost', 10 FROM t_chain_user WHERE 1 = 0")
                .insert();
        assertEquals(0L, zeroInsert.affectedRows());

        // 6. Batch 参数个数校验：行参数数量与占位符不匹配时立即抛异常
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.sql("UPDATE t_chain_user SET user_name = ? WHERE id = ?")
                        .batch(List.<Object[]>of(new Object[]{"only-one-arg"})));

        // 7. RenderContext 拒绝未连接表的悬空 JOIN，并支持 Exprs.col("t1", ...) 自关联投影与条件
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> com.sondertara.joya.core.query.dsl.Query.of(ChainUser.class)
                        .join(DeptExt::getId, SchemaScopedAudit::getId)
                        .toSqlStatement());

        SqlStatement selfJoinSelect = com.sondertara.joya.core.query.dsl.Query.of(EmpSelf.class)
                .leftJoin(EmpSelf::getManagerId, EmpSelf::getId)
                .where(w -> w.eq(com.sondertara.joya.core.query.dsl.Exprs.col("t1", EmpSelf::getEmpName), "Boss"))
                .select(EmpSelf::getEmpName, com.sondertara.joya.core.query.dsl.Exprs.col("t1", EmpSelf::getEmpName))
                .toSqlStatement();
        assertEquals(
                "SELECT t0.emp_name AS c1, t1.emp_name AS c2 FROM t_emp_self t0 LEFT JOIN t_emp_self t1 ON t0.manager_id = t1.id WHERE t1.emp_name = ?",
                selfJoinSelect.sql());

        // 8. Query.limit(2).page(...) 与 count() 保持一致窗口语义，且空结果 totalPages 为 0
        PageResult<ChainUser> limitedPage = joya.from(ChainUser.class)
                .orderByAsc(ChainUser::getId)
                .limit(2)
                .page(PageRequest.of(0, 10));
        assertEquals(2L, limitedPage.getTotal());
        assertEquals(2, limitedPage.getData().size());

        PageResult<ChainUser> emptyPage = joya.from(ChainUser.class)
                .where(w -> w.eq(ChainUser::getId, -999L))
                .page(PageRequest.of(0, 10));
        assertEquals(0L, emptyPage.getTotal());
        assertEquals(0, emptyPage.getTotalPages());

        // 9. PageRequest.getOffset() 整数溢出保护
        org.junit.jupiter.api.Assertions.assertThrows(
                ArithmeticException.class,
                () -> PageRequest.of(Integer.MAX_VALUE, 2).getOffset());

        // 10. sumAsLong / sumAsDecimal 与整型溢出检测
        Long deptSumLong = joya.from(ChainUser.class).sumAsLong(ChainUser::getDeptId).orElseThrow();
        assertEquals(40L, deptSumLong);
        java.math.BigDecimal deptSumDec = joya.from(ChainUser.class).sumAsDecimal(ChainUser::getDeptId).orElseThrow();
        assertEquals(0, new java.math.BigDecimal("40").compareTo(deptSumDec));

        // 11. SQLSERVER_ROW_NUMBER 将 __row_number__ 置于末尾（保留第 1..N 列顺序）并支持 DISTINCT
        SqlStatement distinctStmt = SqlStatement.select("SELECT DISTINCT user_name FROM t_chain_user ORDER BY user_name ASC");
        SqlStatement ssDistinct = Dialects.SQLSERVER_2005.pageSql(distinctStmt, 0, 10);
        assertTrue(ssDistinct.sql().contains("FROM (SELECT DISTINCT user_name FROM t_chain_user) innerTemp"),
                "DISTINCT query must wrap innerTemp before ROW_NUMBER(): " + ssDistinct.sql());
        assertTrue(ssDistinct.sql().contains("SELECT TOP 100 PERCENT innerTemp.*, ROW_NUMBER() OVER (ORDER BY user_name ASC) as __row_number__"),
                "__row_number__ must be placed after user columns: " + ssDistinct.sql());

        // 12. NativeQueryParser 忽略注释与引号内的 ?1，且 IN 集合拒绝 null 元素
        List<ChainUser> commentSafeRows = joya.mapper(ChainUserMapper.class)
                .findWithQuestionMarksInCommentsAndLiterals(2L);
        assertEquals(1, commentSafeRows.size());
        assertEquals("Bob", commentSafeRows.get(0).getUserName());

        List<Long> listWithNull = new java.util.ArrayList<>();
        listWithNull.add(1L);
        listWithNull.add(null);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> joya.from(ChainUser.class).where(w -> w.in(ChainUser::getId, listWithNull)).toSqlStatement());

        // 13. PageQueryParam.clone() 深拷贝验证
        com.sondertara.joya.core.query.pagination.PageQueryParam originalParam =
                new com.sondertara.joya.core.query.pagination.PageQueryParam();
        originalParam.addSearchParam("userName", "Alice", com.sondertara.joya.core.query.pagination.FieldParam.Operator.EQ);
        com.sondertara.joya.core.query.pagination.PageQueryParam clonedParam = originalParam.clone();
        clonedParam.addSearchParam("deptId", 10L, com.sondertara.joya.core.query.pagination.FieldParam.Operator.EQ);
        assertEquals(1, originalParam.getParams().size(), "Modifying cloned PageQueryParam must not mutate original params");
    }

    @Test
    void sqlLexerDialectRules_coverMysqlSqlServerPostgresH2AndZeroIndexedPlaceholderRejection() throws Exception {
        // 1. MySQL: # 行注释 + 'a\' b' 反斜杠转义后跟空格 + 前导 # 注释下 DML 识别
        String mysqlSql = "# comment ?99 '\nSELECT * FROM t_user WHERE note = 'a\\' b ?98' AND id = ?1 AND status = ?2";
        assertEquals(
                "# comment ?99 '\nSELECT * FROM t_user WHERE note = 'a\\' b ?98' AND id = ? AND status = ?",
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(mysqlSql, DbType.mysql));
        assertEquals(2, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(mysqlSql, DbType.mysql));

        var mysqlParsedUpdate = new com.sondertara.joya.core.jdbc.dao.support.NativeQueryParser(
                ChainUser.class,
                ChainUserMapper.class.getMethod("updateWithMysqlHashCommentAndBackslashQuote", Long.class, Long.class),
                DbType.mysql).parse();
        assertEquals(false, mysqlParsedUpdate.isQuery());
        assertEquals(
                List.of(
                        new com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport.ParameterBinding(2),
                        new com.sondertara.joya.core.jdbc.dao.JoyaExecutorSupport.ParameterBinding(1)),
                mysqlParsedUpdate.parameterBinding());

        // 2. SQL Server: #temp 临时表不视作注释、[col?1] 与 [a]]?2] 方括号标识符、ESCAPE '\' 标准单引号
        String sqlServerSql = "SELECT * FROM #temp WHERE [col?1] = 'x' AND [a]]?2] = 'y' AND path LIKE ?1 ESCAPE '\\' AND id = ?2";
        assertEquals(
                "SELECT * FROM #temp WHERE [col?1] = 'x' AND [a]]?2] = 'y' AND path LIKE ? ESCAPE '\\' AND id = ?",
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(sqlServerSql, DbType.sqlserver));
        assertEquals(2, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(sqlServerSql, DbType.sqlserver));

        // 3. PostgreSQL: $$...$$ 与 $tag$...$tag$ 美元引号、E'a\' b' 扩展转义字符串、标准单引号 ESCAPE '\'
        String pgSql = "SELECT $$?99 'unclosed$$, $tag$?98$tag$, E'a\\' b ?97' FROM t_user WHERE code LIKE ?1 ESCAPE '\\' AND id = ?2";
        assertEquals(
                "SELECT $$?99 'unclosed$$, $tag$?98$tag$, E'a\\' b ?97' FROM t_user WHERE code LIKE ? ESCAPE '\\' AND id = ?",
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(pgSql, DbType.postgresql));
        assertEquals(2, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(pgSql, DbType.postgresql));

        // 4. H2 / 标准 SQL: ESCAPE '\' 后接 OR ... ? 不吞掉后续占位符
        String h2EscapeSql = "SELECT * FROM t_chain_user WHERE user_name LIKE ?1 ESCAPE '\\' OR dept_id BETWEEN ?2 AND ?3";
        assertEquals(
                "SELECT * FROM t_chain_user WHERE user_name LIKE ? ESCAPE '\\' OR dept_id BETWEEN ? AND ?",
                com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql(h2EscapeSql, DbType.h2));
        assertEquals(3, com.sondertara.joya.utils.SqlFormatUtils.countPlaceholders(h2EscapeSql, DbType.h2));

        // 5. ?0 零号占位符快速失败
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> com.sondertara.joya.utils.SqlFormatUtils.getJdbcStyleSql("SELECT * FROM t_user WHERE id = ?0", DbType.h2));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new com.sondertara.joya.core.jdbc.dao.support.NativeQueryParser(
                        ChainUser.class,
                        ChainUserMapper.class.getMethod("findWithZeroIndexedPlaceholder", Long.class),
                        DbType.h2).parse());
    }
}
