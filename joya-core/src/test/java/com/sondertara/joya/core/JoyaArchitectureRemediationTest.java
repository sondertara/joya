package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.support.annotation.Query;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterBinder;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@code joya-core-architecture-review.md} 阶段 1–4 全部核心缺陷修复与架构统一的回归测试。
 */
class JoyaArchitectureRemediationTest {

    /** 抽象审计基类：验证父类 @Id 与普通字段继承。 */
    public static abstract class BaseAuditEntity {
        private static final long serialVersionUID = 1L;

        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "created_at")
        private LocalDateTime createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }

    @Entity
    @Table(name = "t_account")
    public static class Account extends BaseAuditEntity {
        @Column(name = "user_name")
        private String userName;

        @Column(name = "dept_id")
        private Long deptId;

        @Column(name = "age")
        private Integer age;

        @Column(name = "balance")
        private BigDecimal balance;

        @Column(name = "active")
        private Boolean active;

        @Column(name = "birth_date")
        private LocalDate birthDate;

        public Account() {
        }

        public Account(Long id, String userName, Long deptId, Integer age, BigDecimal balance, Boolean active,
                       LocalDate birthDate, LocalDateTime createdAt) {
            setId(id);
            setCreatedAt(createdAt);
            this.userName = userName;
            this.deptId = deptId;
            this.age = age;
            this.balance = balance;
            this.active = active;
            this.birthDate = birthDate;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Long getDeptId() {
            return deptId;
        }

        public void setDeptId(Long deptId) {
            this.deptId = deptId;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(BigDecimal balance) {
            this.balance = balance;
        }

        public Boolean getActive() {
            return active;
        }

        public void setActive(Boolean active) {
            this.active = active;
        }

        public LocalDate getBirthDate() {
            return birthDate;
        }

        public void setBirthDate(LocalDate birthDate) {
            this.birthDate = birthDate;
        }
    }

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

    /** 自定义 Mapper 接口：同时验证 JoyaMapper 基础方法、派生查询、@Query 注解与 default 方法。 */
    public interface AccountMapper extends JoyaMapper<Account> {

        List<Account> findByUserName(String userName);

        List<Account> findByDeptIdAndAgeGreaterThan(Long deptId, Integer minAge);

        List<Account> findByUserNameStartingWithOrAgeBetween(String prefix, Integer lowAge, Integer highAge);

        List<Account> findByIdIn(Collection<Long> ids);

        long countByDeptId(Long deptId);

        boolean existsByUserName(String userName);

        List<Account> findFirst2ByDeptId(Long deptId);

        List<Account> findByDeptIdOrderByAgeDesc(Long deptId);

        List<Account> findByDeptIdOrderByAgeAscIdDesc(Long deptId);

        List<Account> findAllByOrderByIdDesc();

        int updateUserNameAndAgeById(String userName, Integer age, Long id);

        int updateById(Account entity, Long id);

        int updateIgnoreNullByUserName(Account patch, String userName);

        int deleteByUserName(String userName);

        @Query("SELECT * FROM t_account WHERE dept_id = ?2 AND age >= ?1 ORDER BY id ASC")
        List<Account> findByOutOfOrderParams(Integer minAge, Long deptId);

        @Query("SELECT balance FROM t_account WHERE id = ?1")
        Optional<BigDecimal> findBalanceById(Long id);

        @Query("UPDATE t_account SET age = ?2 WHERE id = ?1")
        int updateAgeById(Long id, Integer newAge);

        default Optional<Account> findFirstByName(String name) {
            List<Account> list = findByUserName(name);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        }
    }

    private List<SqlStatement> executedStatements;
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_remediation_test;DB_CLOSE_DELAY=-1");

        executedStatements = new ArrayList<>();
        SqlInterceptor recorder = (RewriteContext context, Chain chain) -> {
            executedStatements.add(context.statement());
            return chain.proceed(context);
        };

        joya = JoyaClient.builder(ds).interceptor(recorder).build();
        joya.sql("""
                DROP TABLE IF EXISTS t_account;
                CREATE TABLE t_account (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64),
                    dept_id BIGINT,
                    age INT,
                    balance DECIMAL(18, 2),
                    active BOOLEAN,
                    birth_date DATE,
                    created_at TIMESTAMP
                );
                """).update();

        joya.of(Account.class).insertAll(List.of(
                new Account(1L, "Alice", 10L, 30, new BigDecimal("100.50"), true,
                        LocalDate.of(1996, 5, 1), LocalDateTime.of(2026, 1, 1, 10, 0)),
                new Account(2L, "Bob", 10L, 22, new BigDecimal("200.75"), true,
                        LocalDate.of(2004, 3, 15), LocalDateTime.of(2026, 1, 2, 11, 0)),
                new Account(3L, "Charlie", 20L, 40, new BigDecimal("300.00"), false,
                        null, null)
        ));
        executedStatements.clear();
    }

    @Test
    void whereCriterion_orWrappingPreventsOperatorPrecedenceLeak() {
        // active = true AND (dept_id = 10 OR age = 40)
        // Charlie 的 age = 40 但 active = false；若 OR 未加括号则会误匹配 Charlie！
        List<Account> matched = joya.from(Account.class)
                .where(w -> w.eq(Account::getActive, true)
                        .or(sub -> sub.eq(Account::getDeptId, 10L).eq(Account::getAge, 40)))
                .orderByAsc(Account::getId)
                .list();

        assertEquals(2, matched.size());
        assertEquals("Alice", matched.get(0).getUserName());
        assertEquals("Bob", matched.get(1).getUserName());
        String lastSql = executedStatements.get(executedStatements.size() - 1).sql();
        assertTrue(lastSql.contains("(t0.dept_id = ? OR t0.age = ?)"), "OR 子句必须包裹括号: " + lastSql);
    }

    @Test
    void rawStringCondition_andAddCondition_handleDoubleDigitParameterIndicesCorrectly() {
        // 在已有 9 个条件之后追加 addCondition，验证超过 10 个参数时单趟直接写入 JDBC ? 占位符与参数列表
        List<Account> rows = joya.from(Account.class)
                .where(w -> {
                    for (int i = 0; i < 9; i++) {
                        w.ne(Account::getAge, -100 - i);
                    }
                    w.addCondition("t0.user_name = ? AND t0.dept_id = ?", "Alice", 10L);
                    return w;
                })
                .list();
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).getUserName());
    }

    @Test
    void lateBinding_whereBeforeJoin_unjoinedEntityGuard_andReusableWhereCriterion() {
        joya.sql("""
                DROP TABLE IF EXISTS t_dept;
                CREATE TABLE t_dept (
                    id BIGINT PRIMARY KEY,
                    dept_name VARCHAR(64)
                );
                """).update();
        joya.of(Dept.class).insertAll(List.of(
                new Dept(10L, "R&D"),
                new Dept(20L, "Sales")
        ));

        // 1. 先写 .where(Dept::getDeptName) 与 .orderByAsc(Dept::getId)，再写 .join(Account::getDeptId, Dept::getId)
        // 验证延迟绑定架构下子句顺序正交，主表恒为 t0，关联表恒为 t1
        List<Account> rdAccounts = joya.from(Account.class)
                .where(w -> w.eq(Dept::getDeptName, "R&D"))
                .orderByAsc(Dept::getId)
                .orderByAsc(Account::getId)
                .join(Account::getDeptId, Dept::getId)
                .list();
        assertEquals(2, rdAccounts.size());
        assertEquals("Alice", rdAccounts.get(0).getUserName());
        assertEquals("Bob", rdAccounts.get(1).getUserName());

        // 2. 未声明 join 却在 where 或 select 中引用 Dept::getDeptName，应在渲染期抛 IllegalStateException 阻止隐式笛卡尔积
        assertThrows(IllegalStateException.class, () ->
                joya.from(Account.class)
                        .where(w -> w.eq(Dept::getDeptName, "R&D"))
                        .toSqlStatement());
        assertThrows(IllegalStateException.class, () ->
                joya.from(Account.class)
                        .select(Account::getUserName, Dept::getDeptName)
                        .toSqlStatement());

        // 3. 同一份 WhereCriterion 实例同时复用于多表查询（带 t0. 别名）与单表更新/删除（无表别名）
        WhereCriterion sharedWhere = WhereCriterion.create().eq(Account::getUserName, "Alice");

        SqlStatement selectStmt = joya.from(Account.class).where(sharedWhere).toSqlStatement();
        assertTrue(selectStmt.sql().contains("WHERE t0.user_name = ?"), selectStmt.sql());

        SqlStatement updateStmt = joya.of(Account.class).update()
                .set(Account::getAge, 31)
                .where(sharedWhere)
                .toSqlStatement();
        assertEquals("UPDATE t_account SET age = ? WHERE user_name = ?", updateStmt.sql());
        assertEquals(List.of(31, "Alice"), updateStmt.args());
    }

    @Test
    void sqlStatement_withTable_replacesOnlyWholeTableIdentifiers() {
        SqlStatement stmt = SqlStatement.select(
                "SELECT order_id, user_order FROM order WHERE order.order_id = ?",
                List.of(1L))
                .withMetadata(StatementMetadata.of(Operation.SELECT, java.util.Set.of(TableRef.of("order"))));

        SqlStatement rewritten = stmt.withTable("order", "order_2026");
        assertEquals("SELECT order_id, user_order FROM order_2026 WHERE order_2026.order_id = ?", rewritten.sql());
        assertTrue(rewritten.metadata().tables().contains(TableRef.of("order_2026")));
    }

    @Test
    void inheritedFields_andNullTemporals_andBigDecimalScalarMapping_workEndToEnd() {
        // 1. 验证继承自 BaseAuditEntity 的 id 与 createdAt，以及 Charlie 的 NULL birth_date / created_at 不抛 NPE
        Account charlie = joya.of(Account.class).findById(3L).orElseThrow();
        assertEquals(3L, charlie.getId());
        assertEquals("Charlie", charlie.getUserName());
        assertNull(charlie.getBirthDate());
        assertNull(charlie.getCreatedAt());

        Account alice = joya.of(Account.class).findById(1L).orElseThrow();
        assertEquals(LocalDate.of(1996, 5, 1), alice.getBirthDate());
        assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0), alice.getCreatedAt());

        // 2. 验证 SingleColumnRowMapper / SqlOps 对 BigDecimal、Double、LocalDate、LocalDateTime 的标量读取
        BigDecimal balance = joya.sql("SELECT balance FROM t_account WHERE id = ?", 1L)
                .value(BigDecimal.class)
                .orElseThrow();
        assertEquals(0, new BigDecimal("100.50").compareTo(balance));

        Double balanceAsDouble = joya.sql("SELECT age FROM t_account WHERE id = ?", 1L)
                .value(Double.class)
                .orElseThrow();
        assertEquals(30.0d, balanceAsDouble);

        List<LocalDate> dates = joya.sql("SELECT birth_date FROM t_account WHERE id IN (1, 3) ORDER BY id")
                .list(LocalDate.class);
        assertEquals(2, dates.size());
        assertEquals(LocalDate.of(1996, 5, 1), dates.get(0));
        assertNull(dates.get(1));
    }

    @Test
    void entityOps_batchAndTruncate_populateTableMetadata_andCustomParameterBinderIsHonored() {
        // 1. 验证自定义 ParameterBinder 被 JdbcExecutor 调用
        AtomicBoolean customBinderCalled = new AtomicBoolean(false);
        ParameterBinder customBinder = (ps, idx, param) -> {
            customBinderCalled.set(true);
            ps.setString(idx, param.value().toString().toUpperCase());
        };
        SqlParameter param = new SqlParameter(1, "user_name", "alice", ParameterSource.WHERE, customBinder);
        SqlStatement stmt = SqlStatement.of(
                "SELECT count(*) FROM t_account WHERE UPPER(user_name) = ?",
                List.of(param),
                StatementMetadata.of(Operation.SELECT),
                false);
        assertEquals(1L, joya.sql(stmt).count());
        assertTrue(customBinderCalled.get(), "JdbcExecutor 应调用 SqlParameter 自定义 ParameterBinder");

        // 2. 验证 updateAll / insertAll 携带表名与实体类型元数据
        Account bob = joya.of(Account.class).findById(2L).orElseThrow();
        bob.setAge(23);
        executedStatements.clear();
        joya.of(Account.class).updateAll(List.of(bob));
        assertEquals(1, executedStatements.size());
        assertEquals(Account.class, executedStatements.get(0).metadata().entityType());
        assertTrue(executedStatements.get(0).metadata().tables().contains(TableRef.of("t_account")));

        // 3. 验证 truncate 携带表名与实体类型元数据
        executedStatements.clear();
        joya.of(Account.class).truncate();
        assertEquals(1, executedStatements.size());
        assertEquals(Account.class, executedStatements.get(0).metadata().entityType());
        assertTrue(executedStatements.get(0).metadata().tables().contains(TableRef.of("t_account")));
        assertEquals(0L, joya.of(Account.class).count());
    }

    @Test
    void mapperProxy_supportsBaseCrud_derivedQueries_queryAnnotation_andDefaultMethods() {
        AccountMapper mapper = joya.mapper(AccountMapper.class);
        assertSame(mapper, joya.mapper(AccountMapper.class));
        assertNotNull(mapper.toString());
        assertEquals(mapper, mapper);

        // 1. JoyaMapper 基础方法委托
        assertEquals(3L, mapper.count());
        assertTrue(mapper.existsById(1L));
        assertEquals("Alice", mapper.findById(1L).orElseThrow().getUserName());

        // 2. 派生查询：findByUserName & default 方法
        assertEquals("Alice", mapper.findFirstByName("Alice").orElseThrow().getUserName());

        // 3. 派生查询：AND + GreaterThan
        List<Account> dept10OlderThan25 = mapper.findByDeptIdAndAgeGreaterThan(10L, 25);
        assertEquals(1, dept10OlderThan25.size());
        assertEquals("Alice", dept10OlderThan25.get(0).getUserName());

        // 4. 派生查询：StartingWith OR Between
        List<Account> orResult = mapper.findByUserNameStartingWithOrAgeBetween("Ch", 20, 25);
        assertEquals(2, orResult.size()); // Bob (age 22) + Charlie (starts with Ch)

        // 5. 派生查询：IN 集合展开
        List<Account> inResult = mapper.findByIdIn(List.of(1L, 3L));
        assertEquals(2, inResult.size());

        // 6. 派生查询：countBy / existsBy / findFirst2By / OrderBy
        assertEquals(2L, mapper.countByDeptId(10L));
        assertTrue(mapper.existsByUserName("Bob"));
        assertFalse(mapper.existsByUserName("Nobody"));
        assertEquals(2, mapper.findFirst2ByDeptId(10L).size());

        List<Account> orderedDesc = mapper.findByDeptIdOrderByAgeDesc(10L);
        assertEquals(2, orderedDesc.size());
        assertEquals("Alice", orderedDesc.get(0).getUserName()); // age 30
        assertEquals("Bob", orderedDesc.get(1).getUserName());   // age 22

        List<Account> orderedAsc = mapper.findByDeptIdOrderByAgeAscIdDesc(10L);
        assertEquals(2, orderedAsc.size());
        assertEquals("Bob", orderedAsc.get(0).getUserName());   // age 22
        assertEquals("Alice", orderedAsc.get(1).getUserName()); // age 30

        List<Account> allDesc = mapper.findAllByOrderByIdDesc();
        assertEquals(3, allDesc.size());
        assertEquals(3L, allDesc.get(0).getId());
        assertEquals(1L, allDesc.get(2).getId());

        // 7. @Query 注解（乱序 ?2 ?1、Optional<BigDecimal> 标量、UPDATE 修改语句）
        List<Account> customQueryRows = mapper.findByOutOfOrderParams(25, 10L);
        assertEquals(1, customQueryRows.size());
        assertEquals("Alice", customQueryRows.get(0).getUserName());

        Optional<BigDecimal> aliceBalance = mapper.findBalanceById(1L);
        assertTrue(aliceBalance.isPresent());
        assertEquals(0, new BigDecimal("100.50").compareTo(aliceBalance.get()));

        int updated = mapper.updateAgeById(1L, 31);
        assertEquals(1, updated);
        assertEquals(31, mapper.findById(1L).orElseThrow().getAge());

        // 8. 派生更新（Mode A：按方法名声明 SET 列；Mode B：传实体全量/非空条件更新）
        int modeAUpdated = mapper.updateUserNameAndAgeById("AliceRenamed", 35, 1L);
        assertEquals(1, modeAUpdated);
        Account aliceAfterModeA = mapper.findById(1L).orElseThrow();
        assertEquals("AliceRenamed", aliceAfterModeA.getUserName());
        assertEquals(35, aliceAfterModeA.getAge());

        Account patchEntity = new Account();
        patchEntity.setAge(29);
        int modeBIgnoreNull = mapper.updateIgnoreNullByUserName(patchEntity, "Bob");
        assertEquals(1, modeBIgnoreNull);
        Account bobAfterPatch = mapper.findById(2L).orElseThrow();
        assertEquals("Bob", bobAfterPatch.getUserName()); // 未被 null 覆盖
        assertEquals(29, bobAfterPatch.getAge());

        Account fullEntity = mapper.findById(1L).orElseThrow();
        fullEntity.setUserName("Alice");
        fullEntity.setAge(30);
        int modeBFull = mapper.updateById(fullEntity, 1L);
        assertEquals(1, modeBFull);
        assertEquals("Alice", mapper.findById(1L).orElseThrow().getUserName());

        // 9. 派生删除：deleteByUserName
        int deleted = mapper.deleteByUserName("Charlie");
        assertEquals(1, deleted);
        assertEquals(2L, mapper.count());
    }

    @Entity
    @Table(name = "t_dept")
    public static class AuditedDept extends Dept implements EntityListener {
        private transient boolean persistCalled;
        private transient boolean updateCalled;

        public AuditedDept() {
        }

        public AuditedDept(Long id, String deptName) {
            super(id, deptName);
        }

        @Override
        public void prePersist(Object entity) {
            this.persistCalled = true;
            if (getDeptName() == null) {
                setDeptName("DEFAULT_DEPT");
            }
        }

        @Override
        public void preUpdate(Object entity) {
            this.updateCalled = true;
        }
    }

    @Test
    void saveAll_invokesPrePersistOnInsertedEntities_andPreUpdateOnExistingEntities_andBeanRowMapperIsCached() {
        joya.sql("""
                DROP TABLE IF EXISTS t_dept;
                CREATE TABLE t_dept (
                    id BIGINT PRIMARY KEY,
                    dept_name VARCHAR(64)
                );
                """).update();

        joya.of(AuditedDept.class).insert(new AuditedDept(1L, "Existing"));

        AuditedDept existing = new AuditedDept(1L, "ExistingUpdated");
        AuditedDept fresh = new AuditedDept(2L, null);
        joya.of(AuditedDept.class).saveAll(List.of(existing, fresh));

        assertTrue(existing.updateCalled, "已存在实体在 saveAll 中应触发 preUpdate");
        assertFalse(existing.persistCalled);
        assertTrue(fresh.persistCalled, "新增实体在 saveAll 中应触发 prePersist");
        assertFalse(fresh.updateCalled);
        assertEquals("DEFAULT_DEPT", joya.of(AuditedDept.class).findById(2L).orElseThrow().getDeptName());

        // BeanRowMapper.of 使用 ClassValue 缓存同一实例
        assertSame(BeanRowMapper.of(Account.class), BeanRowMapper.of(Account.class));
    }

    @Test
    void joyaSettings_instanceLevelMaxJoinTable_andUppercaseFormattingWork() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:joya_remediation_test;DB_CLOSE_DELAY=-1");

        JoyaSettings customSettings = JoyaSettings.builder()
                .maxJoinTable(1)
                .tableUppercase(true)
                .columnUppercase(true)
                .build();
        JoyaClient customClient = JoyaClient.builder(ds).settings(customSettings).build();

        // 1. 验证 tableUppercase 与 columnUppercase 在单表与多表 SQL 中生效
        SqlStatement selectStmt = customClient.from(Account.class)
                .where(w -> w.eq(Account::getUserName, "Alice"))
                .select(Account::getId, Account::getUserName)
                .toSqlStatement(Account.class);
        assertEquals("SELECT t0.ID AS id, t0.USER_NAME AS userName FROM T_ACCOUNT t0 WHERE t0.USER_NAME = ?", selectStmt.sql());

        SqlStatement updateStmt = customClient.of(Account.class).update()
                .set(Account::getAge, 33)
                .where(w -> w.eq(Account::getId, 1L))
                .toSqlStatement();
        assertEquals("UPDATE T_ACCOUNT SET AGE = ? WHERE ID = ?", updateStmt.sql());

        // 2. 验证 maxJoinTable = 1 时，主表 + 1 张关联表（共 2 张表）超出上限立即抛异常
        assertThrows(com.sondertara.joya.exceptions.DbException.class, () ->
                customClient.from(Account.class)
                        .join(Account::getDeptId, Dept::getId)
                        .toSqlStatement());
    }
}
