package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.Expr;
import com.sondertara.joya.core.query.dsl.Exprs;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.exceptions.DbException;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表别名（JOIN 显式别名 / 自连接 / 同表多次关联）、类型安全列别名（{@code Expr.as(IGetter)}）与列间比较单元测试。
 */
class AliasAndColumnCompareTest {

    @Entity
    @Table(name = "t_order")
    public static class Order {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "buyer_id")
        private Long buyerId;

        @Column(name = "seller_id")
        private Long sellerId;

        @Column(name = "amount")
        private BigDecimal amount;

        public Long getId() {
            return id;
        }

        public Long getBuyerId() {
            return buyerId;
        }

        public Long getSellerId() {
            return sellerId;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    @Entity
    @Table(name = "t_user")
    public static class User {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "level")
        private Integer level;

        @Column(name = "dept_id")
        private Long deptId;

        @Column(name = "manager_id")
        private Long managerId;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Integer getLevel() {
            return level;
        }

        public Long getDeptId() {
            return deptId;
        }

        public Long getManagerId() {
            return managerId;
        }
    }

    public static class OrderVO {
        private Long id;
        private String buyerName;
        private String sellerName;
        private Long orderCount;

        public Long getId() {
            return id;
        }

        public String getBuyerName() {
            return buyerName;
        }

        public String getSellerName() {
            return sellerName;
        }

        public Long getOrderCount() {
            return orderCount;
        }
    }

    public record OrderSummaryRecord(Long id, String buyerName, Long orderCount) {
    }

    @Test
    void asWithDtoGetter_emitsExactlyThePropertyName() {
        assertEquals(
                "SELECT t0.id AS id, t1.name AS buyerName FROM t_order t0 LEFT JOIN t_user t1 ON t0.buyer_id = t1.id",
                Query.of(Order.class)
                        .leftJoin(Order::getBuyerId, User::getId)
                        .select(Order::getId, Exprs.col("t1", User::getName).as(OrderVO::getBuyerName))
                        .toSqlStatement(OrderVO.class)
                        .sql());
    }

    @Test
    void asWithRecordAccessorAndSingleArgCol_emitsPropertyNameWithoutTableAlias() {
        assertEquals(
                "SELECT t0.id AS id, t1.name AS buyerName, COUNT(t0.id) AS orderCount FROM t_order t0"
                        + " LEFT JOIN t_user t1 ON t0.buyer_id = t1.id GROUP BY t0.id, t1.name",
                Query.of(Order.class)
                        .leftJoin(Order::getBuyerId, User::getId)
                        .groupBy(Order::getId, User::getName)
                        .select(
                                Order::getId,
                                Exprs.col(User::getName).as(OrderSummaryRecord::buyerName),
                                Exprs.count(Order::getId).as(OrderSummaryRecord::orderCount))
                        .toSqlStatement(OrderSummaryRecord.class)
                        .sql());
    }

    @Test
    void asWithDtoGetter_rejectsTypeMismatchUnderErasure() {
        Expr<?> sum = Exprs.sum(Order::getAmount);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Expr<Long> raw = (Expr) sum;
        assertThrows(IllegalArgumentException.class, () -> raw.as(OrderVO::getOrderCount));
    }

    @Test
    void asWithString_rejectsNonIdentifierAndDot() {
        assertThrows(IllegalArgumentException.class, () -> Exprs.count().as("x; DROP TABLE t_order --"));
        assertThrows(IllegalArgumentException.class, () -> Exprs.count().as("has space"));
        assertThrows(IllegalArgumentException.class, () -> Exprs.count().as("a.b"));
        assertThrows(IllegalArgumentException.class, () -> Exprs.count().as(""));
    }

    @Test
    void asReusedAcrossSelectHavingOrderBy() {
        Expr<Long> total = Exprs.count(Order::getId).as(OrderVO::getOrderCount);
        SqlStatement stmt = Query.of(Order.class)
                .groupBy(Order::getBuyerId)
                .having(h -> h.gt(total, 5L))
                .orderByDesc(total)
                .select(Order::getBuyerId, total)
                .toSqlStatement();
        assertEquals(
                "SELECT t0.buyer_id AS c1, COUNT(t0.id) AS c2 FROM t_order t0"
                        + " GROUP BY t0.buyer_id HAVING COUNT(t0.id) > ? ORDER BY COUNT(t0.id) DESC",
                stmt.sql());
        assertEquals(1, stmt.args().size());
    }

    @Test
    void sameColumnFromTwoAliases_needsAliasForNamedMapping() {
        assertThrows(IllegalStateException.class, () ->
                Query.of(Order.class)
                        .leftJoin(Order::getBuyerId, User::getId)
                        .leftJoin(Order::getSellerId, User::getId)
                        .select(Exprs.col("t1", User::getName), Exprs.col("t2", User::getName))
                        .toSqlStatement(OrderVO.class));
    }

    @Test
    void sameTableTwice_withExplicitAliases_mapsByNameAndComparesColumns() {
        SqlStatement stmt = Query.of(Order.class)
                .leftJoin(Order::getBuyerId, User::getId, "buyer")
                .leftJoin(Order::getSellerId, User::getId, "seller")
                .where(w -> w.gt(Exprs.col("buyer", User::getLevel), Exprs.col("seller", User::getLevel)))
                .select(
                        Order::getId,
                        Exprs.col("buyer", User::getName).as(OrderVO::getBuyerName),
                        Exprs.col("seller", User::getName).as(OrderVO::getSellerName))
                .toSqlStatement(OrderVO.class);
        assertNotNull(stmt);
        assertEquals(
                "SELECT t0.id AS id, buyer.name AS buyerName, seller.name AS sellerName FROM t_order t0"
                        + " LEFT JOIN t_user buyer ON t0.buyer_id = buyer.id"
                        + " LEFT JOIN t_user seller ON t0.seller_id = seller.id"
                        + " WHERE buyer.level > seller.level",
                stmt.sql());
        assertTrue(stmt.args().isEmpty());
    }

    @Test
    void selfJoinSameColumn_requiresExplicitAlias() {
        assertThrows(DbException.class, () ->
                Query.of(User.class)
                        .join(User::getDeptId, User::getDeptId)
                        .toSqlStatement());

        SqlStatement withAlias = Query.of(User.class)
                .join(User::getDeptId, User::getDeptId, "peer",
                        on -> on.ne(Exprs.col("t0", User::getId), Exprs.col("peer", User::getId)))
                .select(User::getName, Exprs.col("peer", User::getName).as("peerName"))
                .toSqlStatement();
        assertEquals(
                "SELECT t0.name AS c1, peer.name AS c2 FROM t_user t0"
                        + " JOIN t_user peer ON t0.dept_id = peer.dept_id AND t0.id <> peer.id",
                withAlias.sql());
    }

    @Test
    void multiLevelSelfJoin_withExplicitAliases() {
        SqlStatement stmt = Query.of(User.class)
                .leftJoin(User::getManagerId, User::getId, "mgr")
                .leftJoin(Exprs.col("mgr", User::getManagerId), User::getId, "director")
                .select(
                        User::getName,
                        Exprs.col("mgr", User::getName).as("mgrName"),
                        Exprs.col("director", User::getName).as("directorName"))
                .toSqlStatement();
        assertEquals(
                "SELECT t0.name AS c1, mgr.name AS c2, director.name AS c3 FROM t_user t0"
                        + " LEFT JOIN t_user mgr ON t0.manager_id = mgr.id"
                        + " LEFT JOIN t_user director ON mgr.manager_id = director.id",
                stmt.sql());
    }

    @Test
    void duplicateAndInvalidTableAlias_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                Query.of(Order.class).leftJoin(Order::getBuyerId, User::getId, "bad alias"));
        assertThrows(IllegalArgumentException.class, () ->
                Query.of(Order.class)
                        .leftJoin(Order::getBuyerId, User::getId, "dup")
                        .leftJoin(Order::getSellerId, User::getId, "dup")
                        .toSqlStatement());
    }

    @Test
    void columnComparison_allSixOperatorsAndEmitsNoJdbcParameter() {
        SqlStatement stmt = Query.of(Order.class)
                .join(Order::getBuyerId, User::getId)
                .where(w -> w.eq(Order::getId, User::getId)
                        .ne(Order::getId, User::getDeptId)
                        .gt(Order::getAmount, User::getLevel)
                        .gte(Order::getAmount, User::getLevel)
                        .lt(Order::getBuyerId, Order::getSellerId)
                        .lte(Order::getBuyerId, Order::getSellerId))
                .select(Order::getId)
                .toSqlStatement();
        assertEquals(
                "SELECT t0.id AS c1 FROM t_order t0 JOIN t_user t1 ON t0.buyer_id = t1.id"
                        + " WHERE t0.id = t1.id AND t0.id <> t1.dept_id"
                        + " AND t0.amount > t1.level AND t0.amount >= t1.level"
                        + " AND t0.buyer_id < t0.seller_id AND t0.buyer_id <= t0.seller_id",
                stmt.sql());
        assertTrue(stmt.args().isEmpty(), "column-to-column comparison must not bind parameters: " + stmt.args());
    }

    @Test
    void neUsesSameOperatorForValueAndColumn() {
        assertTrue(Query.of(Order.class).join(Order::getBuyerId, User::getId)
                .where(w -> w.ne(Order::getId, 1L)).toSqlStatement().sql().contains("<> ?"));
        assertTrue(Query.of(Order.class).join(Order::getBuyerId, User::getId)
                .where(w -> w.ne(Order::getId, User::getId)).toSqlStatement().sql().contains("t0.id <> t1.id"));
    }

    @Test
    void unknownAliasListsAvailableAliases() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                Query.of(Order.class)
                        .leftJoin(Order::getBuyerId, User::getId, "buyer")
                        .where(w -> w.eq(Exprs.col("t9", User::getName), "x"))
                        .toSqlStatement());
        assertTrue(e.getMessage().contains("Available aliases"));
        assertTrue(e.getMessage().contains("buyer"));
    }

    @Test
    void n1_reversedJoinColumnsOnSecondSameTableJoin_bindsAliasToTargetTableNotRoot() {
        SqlStatement stmt = Query.of(Order.class)
                .leftJoin(Order::getBuyerId, User::getId)
                .leftJoin(User::getId, Order::getSellerId, "seller")
                .select(Order::getId, Exprs.col("seller", User::getName).as(OrderVO::getSellerName))
                .toSqlStatement(OrderVO.class);
        assertEquals(
                "SELECT t0.id AS id, seller.name AS sellerName FROM t_order t0"
                        + " LEFT JOIN t_user t1 ON t0.buyer_id = t1.id"
                        + " LEFT JOIN t_user seller ON seller.id = t0.seller_id",
                stmt.sql());
    }

    @Test
    void n2_n3_n4_joinEqualsIncludesOnCriterionAndDirectionAndNullAliasHandled() {
        SqlStatement twoOnConditions = Query.of(Order.class)
                .join(Order::getBuyerId, User::getId, w -> w.eq(User::getName, "A"))
                .join(Order::getBuyerId, User::getId, w -> w.eq(User::getName, "B"))
                .select(Order::getId)
                .toSqlStatement();
        assertTrue(twoOnConditions.sql().contains("JOIN t_user t1 ON t0.buyer_id = t1.id AND t1.name = ?"));
        assertTrue(twoOnConditions.sql().contains("JOIN t_user t2 ON t0.buyer_id = t2.id AND t2.name = ?"));
        assertEquals(
                java.util.List.of(
                        com.sondertara.joya.core.kernel.statement.ParameterSource.JOIN,
                        com.sondertara.joya.core.kernel.statement.ParameterSource.JOIN),
                twoOnConditions.parameters().stream()
                        .map(com.sondertara.joya.core.kernel.statement.SqlParameter::source)
                        .toList());

        SqlStatement nullAliasStmt = Query.of(Order.class)
                .leftJoin(Order::getBuyerId, User::getId, (String) null)
                .select(Order::getId)
                .toSqlStatement();
        assertTrue(nullAliasStmt.sql().contains("LEFT JOIN t_user t1 ON t0.buyer_id = t1.id"));
    }

    @Test
    void p0_1_1_whereWithOrCriterionWrapsParenthesesToProtectTenantFilter() {
        com.sondertara.joya.core.query.criterion.WhereCriterion orCrit =
                new com.sondertara.joya.core.query.criterion.WhereCriterion(com.sondertara.joya.core.query.LinkType.OR)
                        .eq(User::getName, "A")
                        .eq(User::getName, "B");
        SqlStatement stmt = Query.of(User.class)
                .where(w -> w.eq(User::getDeptId, 10L))
                .where(orCrit)
                .select(User::getId)
                .toSqlStatement();
        assertEquals(
                "SELECT t0.id AS c1 FROM t_user t0 WHERE t0.dept_id = ? AND (t0.name = ? OR t0.name = ?)",
                stmt.sql());
    }

    @Test
    void p1_2_1_5_autoProjectionRejectsAmbiguousColumnOnSelfJoin() {
        record UserNameDTO(String name) {
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Query.of(User.class)
                        .leftJoin(User::getManagerId, User::getId)
                        .toSqlStatement(UserNameDTO.class));
        assertTrue(ex.getMessage().contains("Cannot auto-project non-entity type")
                || ex.getMessage().contains("is ambiguous across tables"));
    }

    @Entity
    @Table(name = "t_ticket_record")
    record TicketRecord(
            @Id @Column(name = "id") Long id,
            @Column(name = "issue") String issue,
            @Column(name = "getaway") String getaway
    ) {
    }

    @Entity
    @Table(name = "t_batch_seq")
    public static class BatchSeqItem {
        @Id
        @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "title")
        private String title;

        @Column(name = "tag")
        private String tag;

        public BatchSeqItem() {
        }

        public BatchSeqItem(String title, String tag) {
            this.title = title;
            this.tag = tag;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getTag() {
            return tag;
        }
    }

    public interface UserLikeMapper extends com.sondertara.joya.core.mapper.JoyaMapper<User> {
        default java.util.List<User> findByNameLike(String pattern) {
            return query().where(w -> w.like(User::getName, pattern)).list();
        }

        default java.util.List<User> findByNameStartingWith(String prefix) {
            return query().where(w -> w.startsWith(User::getName, prefix)).list();
        }
    }

    @Test
    void batch3_and_batch4_regressionsAreFixed() {
        // 3.6: Record 访问器 issue() / getaway() 不被误截断为 sue / away
        var ticketMeta = com.sondertara.joya.core.metadata.LocalTableMetaCache.getMetadataContext(TicketRecord.class);
        assertEquals("issue", ticketMeta.resolveColumn(TicketRecord::issue));
        assertEquals("getaway", ticketMeta.resolveColumn(TicketRecord::getaway));

        // 1.4: SelectStatementFactory 拒绝元数据外非法列名拼接
        var userMeta = com.sondertara.joya.core.metadata.LocalTableMetaCache.getMetadataContext(User.class);
        assertThrows(IllegalArgumentException.class, () ->
                com.sondertara.joya.core.statement.factory.SelectStatementFactory.create(
                        userMeta,
                        null,
                        java.util.Set.of("1; DROP TABLE t_user --")));

        // 1.5-1: underlineColumn 与 stripTableAlias 跳过字符串字面量
        assertEquals(
                "t0.user_name = 'https://t1.myService.com'",
                com.sondertara.joya.core.kernel.statement.SqlFormatUtils.underlineColumn("t0.userName = 'https://t1.myService.com'"));
        assertEquals(
                "user_name = 'https://t0.myService.com'",
                com.sondertara.joya.core.kernel.statement.SqlFormatUtils.stripTableAlias("t0.user_name = 'https://t0.myService.com'"));

        // 1.5-2: 非 PG 方言下块注释内的 /* 不开启嵌套计数吞掉后续占位符
        assertEquals(
                1,
                com.sondertara.joya.core.kernel.statement.SqlFormatUtils.scanPlaceholders("SELECT /* /* */ ? FROM t").size());

        // 3.5-2: LIKE 保持用户通配符不加 %，STARTING_WITH 哨兵不強制触发 ESCAPE
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:alias_batch3_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JoyaClient joya = JoyaClient.builder(ds).build();
        joya.sql("""
                DROP TABLE IF EXISTS t_user;
                CREATE TABLE t_user (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(64),
                    level INT,
                    dept_id BIGINT,
                    manager_id BIGINT
                );
                """).update();
        joya.sql("INSERT INTO t_user (id, name, dept_id) VALUES (1, 'Alice', 10), (2, 'Bob', 10)").update();
        UserLikeMapper mapper = joya.mapper(UserLikeMapper.class);
        java.util.List<User> likeRows = mapper.findByNameLike("Ali%");
        assertEquals(1, likeRows.size());
        assertEquals("Alice", likeRows.get(0).getName());

        java.util.List<User> prefixRows = mapper.findByNameStartingWith("Al");
        assertEquals(1, prefixRows.size());
        assertEquals("Alice", prefixRows.get(0).getName());

        // 3.1: insertAllIgnoreNull 交替列签名保持输入顺序 [1, 2, 3]
        joya.sql("""
                DROP TABLE IF EXISTS t_batch_seq;
                CREATE TABLE t_batch_seq (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(64),
                    tag VARCHAR(64)
                );
                """).update();
        BatchSeqItem a = new BatchSeqItem("A", null);
        BatchSeqItem b = new BatchSeqItem("B", "tagged");
        BatchSeqItem c = new BatchSeqItem("C", null);
        var res = joya.of(BatchSeqItem.class).insertAllIgnoreNull(java.util.List.of(a, b, c));
        assertEquals(java.util.List.of(1L, 2L, 3L), java.util.List.of(a.getId(), b.getId(), c.getId()));
        assertEquals(java.util.List.of(1L, 2L, 3L), res.getGeneratedKeys(Long.class));
    }

    public record PrimitiveLongView(long val) {
    }

    @Entity
    @Table(name = "t_batch_seq")
    public static class ListenedSeqItem extends BatchSeqItem
            implements com.sondertara.joya.core.listener.EntityListener {
        private transient boolean preCalled;
        private transient boolean postCalled;

        public ListenedSeqItem(String title, String tag) {
            super(title, tag);
        }

        @Override
        public void prePersist(Object entity) {
            this.preCalled = true;
        }

        @Override
        public void postPersist(Object entity) {
            this.postCalled = true;
        }
    }

    @Test
    void batch5_batch6_batch7_regressionsAreFixed() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:batch567_test;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        JoyaClient joya = JoyaClient.create(ds);

        joya.sql("CREATE TABLE IF NOT EXISTS t_batch_seq (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(64), tag VARCHAR(64))").update();
        joya.sql("TRUNCATE TABLE t_batch_seq").update();

        // 1. [3.3] EntityInsert.values(entity).execute() 触发 EntityListener 并回写自增主键
        ListenedSeqItem listened = new ListenedSeqItem("EntityInsertListener", null);
        var insertRes = joya.of(ListenedSeqItem.class).insert().values(listened).ignoreNulls().execute();
        assertTrue(listened.preCalled);
        assertTrue(listened.postCalled);
        assertNotNull(listened.getId());
        assertEquals(listened.getId(), insertRes.getGeneratedKey(Long.class));

        // 2. [2.2-1] Select1 + 单字段基本类型 record 构造器遇到 NULL 列时回退为默认值 0L 而非抛 NPE
        PrimitiveLongView view = joya.from(BatchSeqItem.class)
                .where(w -> w.eq(BatchSeqItem::getId, -999L))
                .select(Exprs.max(BatchSeqItem::getId))
                .one(PrimitiveLongView::new)
                .orElseThrow();
        assertEquals(0L, view.val());

        // 3. [2.2-4] 跨表 5 列 groupBy 与 9 列 select 正常编译并生成 SQL
        SqlStatement group5AndSelect9 = Query.of(Order.class)
                .leftJoin(Order::getBuyerId, User::getId, "buyer")
                .groupBy(
                        Order::getId,
                        Order::getBuyerId,
                        Order::getSellerId,
                        Order::getAmount,
                        User::getDeptId)
                .select(
                        Order::getId,
                        Order::getBuyerId,
                        Order::getSellerId,
                        Order::getAmount,
                        User::getId,
                        User::getName,
                        User::getLevel,
                        User::getDeptId,
                        User::getManagerId)
                .toSqlStatement();
        assertTrue(group5AndSelect9.sql().contains("GROUP BY t0.id, t0.buyer_id, t0.seller_id, t0.amount, buyer.dept_id"));

        // 4. [2.3-1 & 2.3-3] TableRef 自动剥离引号；4.1-4 SqlParameter 拒绝 index=0 且 replaceParameter 保留自定义 binder
        com.sondertara.joya.core.kernel.statement.TableRef ref =
                com.sondertara.joya.core.kernel.statement.TableRef.ofQualified("`app`.`t_user`", "t0");
        assertEquals("app", ref.schema());
        assertEquals("t_user", ref.name());

        assertThrows(IndexOutOfBoundsException.class, () ->
                com.sondertara.joya.core.kernel.statement.SqlParameter.of(
                        0, "p", 1, com.sondertara.joya.core.kernel.statement.ParameterSource.RAW));

        com.sondertara.joya.core.kernel.statement.ParameterBinder customBinder = (ps, idx, val) -> ps.setObject(idx, val);
        SqlStatement stmtWithCustomBinder = SqlStatement.select("SELECT * FROM t_batch_seq WHERE id = ?", 1L)
                .replaceParameter(1, com.sondertara.joya.core.kernel.statement.SqlParameter.of(
                        1, "id", 2L, com.sondertara.joya.core.kernel.statement.ParameterSource.WHERE, customBinder));
        assertEquals(customBinder, stmtWithCustomBinder.parameters().get(0).binder());

        // 5. [4.1-1 & 4.1-3] 执行期自动填充已缓存 dbType
        assertEquals(com.sondertara.joya.core.kernel.statement.DbType.h2, joya.dbType());
        assertEquals(1, joya.sql(SqlStatement.select("SELECT 1"))
                .one(new com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper<>(Integer.class))
                .orElseThrow());

        // 6. [4.3-1] 流式查询返回未关闭的惰性流
        try (java.util.stream.Stream<Long> stream = joya.sql(SqlStatement.select("SELECT id FROM t_batch_seq"))
                .stream(new com.sondertara.joya.core.kernel.executor.SingleColumnRowMapper<>(Long.class))) {
            assertEquals(1L, stream.count());
        }

        // 7. [5.2-5] 死锁重试期间 afterCompletion 仅在最终尝试完成时触发一次
        java.util.concurrent.atomic.AtomicInteger completionCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger tries = new java.util.concurrent.atomic.AtomicInteger();
        joya.tx().retryOnDeadlock(3).run(tx -> {
            tx.afterCompletion(o -> completionCalls.incrementAndGet());
            if (tries.incrementAndGet() < 2) {
                throw new com.sondertara.joya.exceptions.DbException(
                        new java.sql.SQLException("deadlock", "40001"));
            }
        });
        assertEquals(1, completionCalls.get());
    }
}
