package com.sondertara.joya.core;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.Exprs;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SQL 文本快照测试 —— 任何"行为不变"的重构都必须先让本类保持全绿。
 *
 * <p>覆盖：单表查询、联表查询、部分列投影、分组聚合、DISTINCT、单表 DML、
 * {@code @Column} 混合大小写保留、以及命名策略开启大小写转换后的输出。
 *
 * <p>本类只断言"当前既定行为"，不承担"行为是否正确"的裁判职责。
 * 若重构确实需要改变 SQL 文本，必须同时在此处显式更新期望值，让变更可见。
 */
class SqlGoldenTest {

    @Entity
    @Table(name = "t_dept")
    public static class Dept {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "dept_name")
        private String deptName;

        public Long getId() {
            return id;
        }

        public String getDeptName() {
            return deptName;
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

        public Long getId() {
            return id;
        }

        public Long getDeptId() {
            return deptId;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        public Integer getAge() {
            return age;
        }
    }

    /** 用于验证 {@code @Column(name)} 原始大小写不被抹平。 */
    @Entity
    @Table(name = "t_order")
    public static class Order {
        @Id
        @Column(name = "OrderNo")
        private String orderNo;

        @Column(name = "Amount")
        private Long amount;

        public String getOrderNo() {
            return orderNo;
        }

        public Long getAmount() {
            return amount;
        }
    }

    private static EntityMetadataContext meta(Class<?> type) {
        return CacheHolder.tableMetaCache().getMetadataContext(type);
    }

    private static MutationContext ctx(Class<?> type, Map<String, Object> values, boolean ignoreNull, NamingPolicy policy) {
        EntityMetadataContext meta = meta(type);
        Set<String> columns = new LinkedHashSet<>(meta.columnNames());
        return new MutationContext(meta, values, null, columns, ignoreNull, false, policy);
    }

    private static void check(String name, SqlStatement stmt, String expectedSql, String expectedArgs) {
        assertEquals(expectedSql, stmt.sql(), name + " SQL");
        assertEquals(expectedArgs, stmt.args().toString(), name + " ARGS");
    }

    @Test
    void selectStatements() {
        check("select_all",
                Query.of(User.class).toSqlStatement(),
                "SELECT t0.id, t0.dept_id, t0.name, t0.email, t0.age FROM t_user t0",
                "[]");

        check("select_where_order_limit",
                Query.of(User.class)
                        .where(w -> w.eq(User::getName, "alice").gt(User::getAge, 18))
                        .orderByDesc(User::getId)
                        .limit(10)
                        .toSqlStatement(),
                "SELECT t0.id, t0.dept_id, t0.name, t0.email, t0.age FROM t_user t0"
                        + " WHERE t0.name = ? AND t0.age > ? ORDER BY t0.id DESC",
                "[alice, 18]");

        check("select_join_projection",
                Query.of(User.class)
                        .join(User::getDeptId, Dept::getId)
                        .where(w -> w.eq(Dept::getDeptName, "RD"))
                        .select(User::getId, User::getName, Dept::getDeptName)
                        .toSqlStatement(),
                "SELECT t0.id AS c1, t0.name AS c2, t1.dept_name AS c3 FROM t_user t0"
                        + " JOIN t_dept t1 ON t0.dept_id = t1.id WHERE t1.dept_name = ?",
                "[RD]");

        check("select_group_having",
                Query.of(User.class)
                        .join(User::getDeptId, Dept::getId)
                        .groupBy(Dept::getDeptName)
                        .having(h -> h.gt(Exprs.count(), 5L))
                        .orderByDesc(Exprs.max(User::getAge))
                        .select(Dept::getDeptName, Exprs.max(User::getAge), Exprs.count())
                        .toSqlStatement(),
                "SELECT t1.dept_name AS c1, MAX(t0.age) AS c2, COUNT(*) AS c3 FROM t_user t0"
                        + " JOIN t_dept t1 ON t0.dept_id = t1.id GROUP BY t1.dept_name"
                        + " HAVING COUNT(*) > ? ORDER BY MAX(t0.age) DESC",
                "[5]");

        check("select_distinct",
                Query.of(User.class).distinct().toSqlStatement(),
                "SELECT DISTINCT t0.id, t0.dept_id, t0.name, t0.email, t0.age FROM t_user t0",
                "[]");

        check("select_mixed_case",
                Query.of(Order.class).toSqlStatement(),
                "SELECT t0.OrderNo, t0.Amount FROM t_order t0",
                "[]");

        check("select_upper",
                SelectStatementFactory.create(meta(User.class),
                        new WhereCriterion().eq(User::getId, 1L), null, null,
                        new NamingPolicy(10, true, true)),
                "SELECT * FROM T_USER WHERE ID = ?",
                "[1]");
    }

    @Test
    void mutationStatements() {
        NamingPolicy plain = NamingPolicy.DEFAULT;

        check("insert_plain",
                InsertStatementFactory.create(ctx(User.class,
                        values("id", 1L, "dept_id", 2L, "name", "alice", "email", null, "age", 20), false, plain)),
                "INSERT INTO t_user (id, dept_id, name, email, age) VALUES (?, ?, ?, ?, ?)",
                "[1, 2, alice, null, 20]");

        check("insert_ignore_null",
                InsertStatementFactory.create(ctx(User.class,
                        values("id", 1L, "dept_id", 2L, "name", "alice", "email", null, "age", 20), true, plain)),
                "INSERT INTO t_user (id, dept_id, name, age) VALUES (?, ?, ?, ?)",
                "[1, 2, alice, 20]");

        check("insert_upper",
                InsertStatementFactory.create(ctx(User.class,
                        values("id", 1L, "dept_id", 2L, "name", "alice", "email", "a@b.c", "age", 20), false,
                        new NamingPolicy(10, true, true))),
                "INSERT INTO T_USER (ID, DEPT_ID, NAME, EMAIL, AGE) VALUES (?, ?, ?, ?, ?)",
                "[1, 2, alice, a@b.c, 20]");

        check("insert_mixed_case",
                InsertStatementFactory.create(ctx(Order.class,
                        values("OrderNo", "A-1", "Amount", 100L), false, plain)),
                "INSERT INTO t_order (OrderNo, Amount) VALUES (?, ?)",
                "[A-1, 100]");

        check("update_where",
                UpdateStatementFactory.create(new MutationContext(meta(User.class),
                        values("name", "bob", "age", 30),
                        new WhereCriterion().eq(User::getId, 9L),
                        orderedSet("name", "age"), false, false, plain)),
                "UPDATE t_user SET name = ?, age = ? WHERE id = ?",
                "[bob, 30, 9]");

        check("delete_where",
                DeleteStatementFactory.create(new MutationContext(meta(User.class), Map.of(),
                        new WhereCriterion().eq(User::getId, 9L),
                        Set.of(), false, false, plain)),
                "DELETE FROM t_user WHERE id = ?",
                "[9]");

        check("delete_allow_all",
                DeleteStatementFactory.create(new MutationContext(meta(User.class), Map.of(),
                        null, Set.of(), false, true, plain)),
                "DELETE FROM t_user",
                "[]");
    }

    private static Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
