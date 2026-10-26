package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DbType;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.pagination.FieldParam;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.PageQueryParams;
import com.sondertara.joya.core.query.pagination.PageRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sondertara.joya.core.query.dsl.Exprs.count;
import static com.sondertara.joya.core.query.dsl.Exprs.max;
import static com.sondertara.joya.core.query.dsl.Exprs.raw;
import static com.sondertara.joya.core.query.dsl.Exprs.sum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应 {@code query-dsl-redesign-plan.md} 第 8 节测试清单的完整专项验证。
 */
class QueryDslIntegrationTest {

    @Entity
    @Table(name = "t_dept")
    public static class Dept {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "dept_name")
        private String deptName;

        public Dept() {
        }

        public Dept(Long id, String deptName) {
            this.id = id;
            this.deptName = deptName;
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
    }

    @Entity
    @Table(name = "t_user")
    public static class User {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "dept_id")
        private Long deptId;

        @Column(name = "name")
        private String name;

        @Column(name = "email")
        private String email;

        @Column(name = "age")
        private Integer age;

        public User() {
        }

        public User(Long id, Long deptId, String name, String email, Integer age) {
            this.id = id;
            this.deptId = deptId;
            this.name = name;
            this.email = email;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getDeptId() {
            return deptId;
        }

        public void setDeptId(Long deptId) {
            this.deptId = deptId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    /** 宽 DTO：含 @Column 与 @Transient 字段。 */
    public static class UserDeptBriefDto {
        private String name;
        @Column(name = "dept_name")
        private String department;
        @Transient
        private String ignoredRemark = "keep";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }

        public String getIgnoredRemark() {
            return ignoredRemark;
        }

        public void setIgnoredRemark(String ignoredRemark) {
            this.ignoredRemark = ignoredRemark;
        }
    }

    /** 位置映射类型转换验证 Record：Number 承接 Integer、BigDecimal 承接 JDBC INT、基本类型 int 承接 NULL 兜底为 0。 */
    public record ConvertedUserRecord(Long id, Number ageAsNumber, BigDecimal ageAsDecimal, int primitiveAge) {}

    /** 分组统计 Record。 */
    public record DeptStat(String deptName, Integer maxAge, Long userCount) {}

    /** 同类型双字段 Record（用于测试顺序校验）。 */
    public record UserContact(String name, String email) {}

    /** 字段名与实体属性名不同的同类型双字段 Record（不应误报顺序错误）。 */
    public record CustomLabelPair(String firstLabel, String secondLabel) {}

    /** 含不存在字段的 DTO。 */
    public record UnknownFieldDto(String name, String nonExistentField) {}

    /** 含跨表同名字段 id 的 DTO。 */
    public record AmbiguousIdDto(Long id, String name) {}

    private List<SqlStatement> executedStatements;
    private JdbcDataSource dataSource;
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:joya_query_dsl_test;DB_CLOSE_DELAY=-1");

        executedStatements = new ArrayList<>();
        SqlInterceptor recorder = (RewriteContext ctx, Chain chain) -> {
            executedStatements.add(ctx.statement());
            return chain.proceed(ctx);
        };

        joya = JoyaClient.builder(dataSource).interceptor(recorder).build();
        joya.sql("""
                DROP TABLE IF EXISTS t_user;
                DROP TABLE IF EXISTS t_dept;
                CREATE TABLE t_dept (
                    id BIGINT PRIMARY KEY,
                    dept_name VARCHAR(64)
                );
                CREATE TABLE t_user (
                    id BIGINT PRIMARY KEY,
                    dept_id BIGINT,
                    name VARCHAR(64),
                    email VARCHAR(128),
                    age INT
                );
                """).update();

        joya.of(Dept.class).insertAll(List.of(
                new Dept(10L, "R&D"),
                new Dept(20L, "Sales")
        ));
        joya.of(User.class).insertAll(List.of(
                new User(1L, 10L, "Alice", "alice@joya.dev", 30),
                new User(2L, 10L, "Bob", "bob@joya.dev", 25),
                new User(3L, 20L, "Charlie", "charlie@joya.dev", null)
        ));
        executedStatements.clear();
    }

    @Test
    void checklist_1_autoProjectionForJoinedEntityAndWideDtoWithTransient() {
        // join 后不写 select，直接 list(JoinedEntity.class) 查关联表全部列
        List<Dept> depts = joya.from(User.class)
                .join(User::getDeptId, Dept::getId)
                .where(w -> w.eq(User::getName, "Alice"))
                .list(Dept.class);
        assertEquals(1, depts.size());
        assertEquals(10L, depts.get(0).getId());
        assertEquals("R&D", depts.get(0).getDeptName());

        // 宽 DTO 自动跨表匹配无歧义字段（name 来自 User，@Column("dept_name") 来自 Dept，跳过 @Transient）
        List<UserDeptBriefDto> briefs = joya.from(User.class)
                .join(User::getDeptId, Dept::getId)
                .where(w -> w.eq(User::getId, 1L))
                .list(UserDeptBriefDto.class);
        assertEquals(1, briefs.size());
        assertEquals("Alice", briefs.get(0).getName());
        assertEquals("R&D", briefs.get(0).getDepartment());
        assertEquals("keep", briefs.get(0).getIgnoredRemark());
    }

    @Test
    void checklist_3_positionalMappingTypeConversionAndNullPrimitiveFallback() {
        // Charlie 的 age 为 NULL：转基本类型 int 应兜底为 0，转 BigDecimal / Number 为 null
        ConvertedUserRecord charlie = joya.from(User.class)
                .where(w -> w.eq(User::getId, 3L))
                .select(User::getId, User::getAge, raw("t0.age", BigDecimal.class), User::getAge)
                .one(ConvertedUserRecord::new)
                .orElseThrow();
        assertEquals(3L, charlie.id());
        assertNull(charlie.ageAsNumber());
        assertNull(charlie.ageAsDecimal());
        assertEquals(0, charlie.primitiveAge());

        // Alice 的 age 为 30（Integer）：自动转为 Number 30、BigDecimal 30、int 30
        ConvertedUserRecord alice = joya.from(User.class)
                .where(w -> w.eq(User::getId, 1L))
                .select(User::getId, User::getAge, raw("t0.age", BigDecimal.class), User::getAge)
                .one(ConvertedUserRecord::new)
                .orElseThrow();
        assertEquals(1L, alice.id());
        assertEquals(30, alice.ageAsNumber());
        assertEquals(0, BigDecimal.valueOf(30).compareTo(alice.ageAsDecimal()));
        assertEquals(30, alice.primitiveAge());
    }

    @Test
    void checklist_4_5_recordOrderVerifierAndNamedProjectionGuards() {
        // 1. 同名集合但顺序颠倒 → 报错
        assertThrows(IllegalStateException.class, () ->
                joya.from(User.class)
                        .select(User::getEmail, User::getName)
                        .list(UserContact::new));

        // 2. Record 组件名与实体属性名集合不同（如自定义命名 CustomLabelPair）→ 不误报，按位置正常构造
        List<CustomLabelPair> customPairs = joya.from(User.class)
                .where(w -> w.eq(User::getId, 1L))
                .select(User::getEmail, User::getName)
                .list(CustomLabelPair::new);
        assertEquals(List.of(new CustomLabelPair("alice@joya.dev", "Alice")), customPairs);

        // 3. list(X.class) 包含 Expr（聚合或 raw）时报错并提示使用 X::new
        IllegalStateException exprEx = assertThrows(IllegalStateException.class, () ->
                joya.from(User.class)
                        .select(User::getDeptId, count())
                        .list(User.class));
        assertTrue(exprEx.getMessage().contains("::new"));

        // 4. DTO 字段在所有参与表中均不存在时报错
        assertThrows(IllegalStateException.class, () ->
                joya.from(User.class).list(UnknownFieldDto.class));

        // 5. DTO 字段在联表的多张表中同名歧义时报错
        assertThrows(IllegalStateException.class, () ->
                joya.from(User.class)
                        .join(User::getDeptId, Dept::getId)
                        .list(AmbiguousIdDto.class));
    }

    @Test
    void checklist_6_7_paginationCountSqlHasNoOrderBy_andDistinctWorks() {
        executedStatements.clear();

        PageResult<User> page = joya.from(User.class)
                .orderByDesc(User::getId)
                .page(PageRequest.of(0, 2));
        assertEquals(3L, page.getTotal());
        assertEquals(2, page.getData().size());

        // 第一条执行的语句是计数 SQL，绝不能包含 ORDER BY
        String countSql = executedStatements.get(0).sql().toUpperCase();
        assertTrue(countSql.startsWith("SELECT COUNT(*) FROM T_USER"), "计数 SQL 应直接 COUNT(*): " + countSql);
        assertFalse(countSql.contains("ORDER BY"), "计数 SQL 不应包含 ORDER BY: " + countSql);

        // distinct 查询与计数
        List<Long> distinctDeptIds = joya.from(User.class)
                .distinct()
                .orderByAsc(User::getDeptId)
                .select(User::getDeptId)
                .list();
        assertEquals(List.of(10L, 20L), distinctDeptIds);

        // raw 表达式与 sum 配合使用
        List<DeptStat> rawStats = joya.from(User.class)
                .join(User::getDeptId, Dept::getId)
                .groupBy(Dept::getDeptName)
                .orderByAsc(Dept::getDeptName)
                .select(Dept::getDeptName, raw("COALESCE(MAX(t0.age), 0)", Integer.class), sum(raw("1", Long.class)))
                .list(DeptStat::new);
        assertEquals(2, rawStats.size());
        assertEquals(new DeptStat("R&D", 30, 2L), rawStats.get(0));
        assertEquals(new DeptStat("Sales", 0, 1L), rawStats.get(1));
    }

    @Test
    void checklist_9_pageQueryParamsNestedAndOrConditions() {
        PageQueryParam param = new PageQueryParam();
        param.page(0);
        param.pageSize(10);
        param.addSearchParam("deptId", 10L, FieldParam.Operator.EQ);

        PageQueryParam.Nested nested = new PageQueryParam.Nested();
        nested.addOrParam("name", "Alice", FieldParam.Operator.EQ);
        nested.addOrParam("name", "Bob", FieldParam.Operator.EQ);
        param.setNested(nested);
        param.setOrderList(List.of(new OrderParam("age", OrderParam.OrderBy.ASC)));

        PageResult<User> result = joya.from(User.class)
                .apply(PageQueryParams.of(param))
                .page(param.toPageRequest());
        assertEquals(2L, result.getTotal());
        assertEquals("Bob", result.getData().get(0).getName());
        assertEquals("Alice", result.getData().get(1).getName());
    }

    @Test
    void distinctWindowPageUsesCurrentProjectionForTotal() {
        executedStatements.clear();

        PageResult<Long> page = joya.from(User.class)
                .distinct()
                .limit(1, 2)
                .orderByAsc(User::getDeptId)
                .select(User::getDeptId)
                .page(PageRequest.of(0, 1));

        assertEquals(1L, page.getTotal());
        assertEquals(List.of(20L), page.getData());
        String countSql = executedStatements.get(0).sql();
        assertTrue(countSql.contains("SELECT DISTINCT t0.dept_id AS c1"), countSql);
        assertFalse(countSql.contains("t0.id"), countSql);
    }

    @Test
    void globalAggregateProjectionPageWithoutGroupBy_returnsAggregateRowCountAndPreservesEmptyTableResult() {
        executedStatements.clear();

        // 1. 非空表无 GROUP BY 的全局聚合投影分页：结果集只有 1 行聚合行（值为 3L），total 应为 1 而非底表行数 3
        PageResult<Long> nonEmptyAggPage = joya.from(User.class)
                .select(count())
                .page(PageRequest.of(0, 10));
        assertEquals(1L, nonEmptyAggPage.getTotal());
        assertEquals(List.of(3L), nonEmptyAggPage.getData());

        // 2. 空结果过滤（或空表）下的无 GROUP BY 全局聚合投影分页：SQL 全局 COUNT(*) 仍返回 1 行（值为 0L），不得因底表 0 行而跳过数据查询
        PageResult<Long> emptyAggPage = joya.from(User.class)
                .where(w -> w.eq(User::getId, -999L))
                .select(count())
                .page(PageRequest.of(0, 10));
        assertEquals(1L, emptyAggPage.getTotal());
        assertEquals(List.of(0L), emptyAggPage.getData());
    }

    @Test
    void pageQueryParamsBoxesPrimitiveArrays() {
        PageQueryParam param = new PageQueryParam();
        param.addSearchParam("id", new int[]{1, 3}, FieldParam.Operator.IN);

        List<User> users = joya.from(User.class)
                .apply(PageQueryParams.of(param))
                .orderByAsc(User::getId)
                .list();

        assertEquals(List.of(1L, 3L), users.stream().map(User::getId).toList());
    }

    @Test
    void quotedNamingMatchesDtoColumnAnnotationAgainstRawMetadata() {
        JoyaClient quoted = JoyaClient.builder(dataSource)
                .settings(JoyaSettings.builder()
                        .columnUppercase(true)
                        .tableUppercase(true)
                        .quoteStyle(QuoteStyle.DOUBLE_QUOTE)
                        .build())
                .build();

        List<UserDeptBriefDto> rows = quoted.from(User.class)
                .join(User::getDeptId, Dept::getId)
                .where(w -> w.eq(User::getId, 1L))
                .list(UserDeptBriefDto.class);

        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).getName());
        assertEquals("R&D", rows.get(0).getDepartment());
    }

    @Test
    void nativePageUsesLongOffsetAndZeroLimitIsDialectIndependent() {
        PageResult<User> page = joya.sql("SELECT * FROM t_user ORDER BY id")
                .page(User.class, PageRequest.of(Integer.MAX_VALUE, 2));
        assertEquals(3L, page.getTotal());
        assertTrue(page.getData().isEmpty());

        for (DbType dbType : List.of(DbType.h2, DbType.mysql, DbType.sqlserver, DbType.oracle, DbType.informix)) {
            JoyaClient client = JoyaClient.builder(dataSource).dialect(dbType).build();
            assertTrue(client.sql("SELECT * FROM t_user").limit(User.class, 0, 0).isEmpty(), dbType.name());
            assertThrows(IllegalArgumentException.class,
                    () -> client.sql("SELECT * FROM t_user").limit(User.class, 0, -1), dbType.name());
            assertThrows(IllegalArgumentException.class,
                    () -> client.sql("SELECT * FROM t_user").limit(User.class, -1, 1), dbType.name());
        }
    }

    @Test
    void listMapUsesMapRowMapperSemantics() {
        Map<String, Object> row = joya.sql(
                "SELECT id AS \"MixedId\", name AS \"t0.display_name\" FROM t_user WHERE id = 1")
                .listMap()
                .get(0);

        assertEquals(1L, row.get("mixedid"));
        assertEquals("Alice", row.get("display_name"));
    }

    @Test
    void joinRejectsExpressionWithoutTableRoleAtBuildTime() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> Query.of(User.class).join(raw("t0.dept_id", Long.class), Dept::getId));

        assertTrue(exception.getMessage().contains("JOIN left"), exception.getMessage());
        assertTrue(exception.getMessage().contains("table role"), exception.getMessage());
    }
}
