package com.sondertara.joya.core;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.dao.JoyaMapperImpl;
import com.sondertara.joya.core.kernel.interceptor.Chain;
import com.sondertara.joya.core.kernel.interceptor.RewriteContext;
import com.sondertara.joya.core.kernel.interceptor.SqlInterceptor;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.query.pagination.FieldParam;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.PageQueryParams;
import com.sondertara.joya.core.query.pagination.PageRequest;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.tx.TxOutcome;
import com.sondertara.joya.core.tx.TxTimeoutException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.sondertara.joya.core.query.dsl.Exprs.avg;
import static com.sondertara.joya.core.query.dsl.Exprs.count;
import static com.sondertara.joya.core.query.dsl.Exprs.countDistinct;
import static com.sondertara.joya.core.query.dsl.Exprs.max;
import static com.sondertara.joya.core.query.dsl.Exprs.min;
import static com.sondertara.joya.core.query.dsl.Exprs.sum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joya 统一查询 DSL（{@link Query} / {@link DataOps} / {@link EntityOps} / {@link SqlOps}）
 * 与显式事务架构（{@link com.sondertara.joya.core.tx.Tx}）端到端集成测试。
 */
class JoyaFluentApiAndTxIntegrationTest {

    @Entity
    @Table(name = "t_account")
    public static class Account {
        @Id
        @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "balance")
        private Integer balance;

        @Column(name = "email")
        private String email;

        public Account() {
        }

        public Account(Long id, String name, Integer balance, String email) {
            this.id = id;
            this.name = name;
            this.balance = balance;
            this.email = email;
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

        public Integer getBalance() {
            return balance;
        }

        public void setBalance(Integer balance) {
            this.balance = balance;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    @Entity
    @Table(name = "t_order")
    public static class OrderRecord {
        @Id
        @javax.persistence.GeneratedValue(strategy = javax.persistence.GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "account_id")
        private Long accountId;

        @Column(name = "item_name")
        private String itemName;

        @Column(name = "amount")
        private Integer amount;

        public OrderRecord() {
        }

        public OrderRecord(Long id, Long accountId, String itemName, Integer amount) {
            this.id = id;
            this.accountId = accountId;
            this.itemName = itemName;
            this.amount = amount;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getAccountId() {
            return accountId;
        }

        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        public String getItemName() {
            return itemName;
        }

        public void setItemName(String itemName) {
            this.itemName = itemName;
        }

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }
    }

    /** 跨表位置构造映射 Record。 */
    public record AccountOrderView(Long orderId, int amount, String userName) {}

    /** 分组统计 Record（含聚合结果）。 */
    public record AccountOrderStat(String userName, int maxAmount, long orderCount) {}

    /** 宽 DTO（不写 select，根据字段名自动推列）。 */
    public record AccountBrief(Long id, String name, int balance) {}

    /** 同类型双 String 字段 Record（用于测试列顺序错位防护）。 */
    public record NameEmailPair(String name, String email) {}

    /** 跨表同名字段歧义 Record（id 在 Account 与 OrderRecord 中都存在）。 */
    public record AmbiguousOrderDto(Long id, String itemName) {}

    private JdbcDataSource dsPrimary;
    private JdbcDataSource dsAudit;
    private List<SqlStatement> interceptedStatements;
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        dsPrimary = new JdbcDataSource();
        dsPrimary.setURL("jdbc:h2:mem:joya_test_primary;DB_CLOSE_DELAY=-1");

        dsAudit = new JdbcDataSource();
        dsAudit.setURL("jdbc:h2:mem:joya_test_audit;DB_CLOSE_DELAY=-1");

        interceptedStatements = new ArrayList<>();
        SqlInterceptor recorder = (RewriteContext ctx, Chain chain) -> {
            interceptedStatements.add(ctx.statement());
            return chain.proceed(ctx);
        };

        joya = JoyaClient.builder(dsPrimary)
                .interceptor(recorder)
                .queryTimeoutSeconds(30)
                .build();

        String ddl = """
                DROP TABLE IF EXISTS t_order;
                DROP TABLE IF EXISTS t_account;
                CREATE TABLE t_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64),
                    balance INT,
                    email VARCHAR(128)
                );
                CREATE TABLE t_order (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    account_id BIGINT,
                    item_name VARCHAR(64),
                    amount INT
                );
                """;
        joya.sql(ddl).update();
        JoyaClient.create(dsAudit).sql(ddl).update();
        interceptedStatements.clear();
    }

    // ==================== 1. P0 缺陷回归验证（C1-C4） ====================

    @Test
    void regression_C1_insertReturnsMutationResultAndWritesBackGeneratedKey() {
        Account acc = new Account(null, "Alice", 100, "alice@test.com");
        MutationResult res = joya.of(Account.class).insert(acc);

        assertEquals(1L, res.affectedRows());
        Long generatedId = res.getGeneratedKey(Long.class);
        assertNotNull(generatedId, "insert 应返回数据库自增主键");
        assertEquals(generatedId, acc.getId(), "insert 应将自增主键回写至实体字段");
    }

    @Test
    void regression_C2_entityUpdateSetOnlyModifiesDeclaredColumns() {
        Account acc = new Account(1L, "Alice", 100, "alice@test.com");
        joya.of(Account.class).insert(acc);

        // 链式局部更新：只改 balance，不得将 name 和 email 刷成 NULL
        MutationResult res = joya.of(Account.class).update()
                .set(Account::getBalance, 250)
                .where(w -> w.eq(Account::getId, 1L))
                .execute();

        assertEquals(1L, res.affectedRows());
        Account loaded = joya.of(Account.class).findById(1L).orElseThrow();
        assertEquals("Alice", loaded.getName(), "未放入 set() 的列绝不能被覆盖为 NULL");
        assertEquals("alice@test.com", loaded.getEmail(), "未放入 set() 的列绝不能被覆盖为 NULL");
        assertEquals(250, loaded.getBalance());
    }

    @Test
    void regression_C2_patchSkipsNullFields() {
        joya.of(Account.class).insert(new Account(1L, "Alice", 100, "alice@test.com"));

        Account partial = new Account(1L, null, 300, null);
        joya.of(Account.class).patch(partial);

        Account loaded = joya.of(Account.class).findById(1L).orElseThrow();
        assertEquals("Alice", loaded.getName());
        assertEquals("alice@test.com", loaded.getEmail());
        assertEquals(300, loaded.getBalance());
    }

    @Test
    void regression_C3_emptyWhereCallbackRejectedOnDeleteAndUpdate() {
        joya.of(Account.class).insert(new Account(1L, "Alice", 100, "alice@test.com"));

        assertThrows(IllegalStateException.class, () ->
                joya.of(Account.class).delete().where(w -> w).execute(),
                "空 WHERE 回调必须被拒绝以防全表误删");

        assertThrows(IllegalStateException.class, () ->
                joya.of(Account.class).update().set(Account::getBalance, 0).where(w -> w).execute(),
                "空 WHERE 回调必须被拒绝以防全表误更新");

        assertEquals(1L, joya.of(Account.class).count(), "被拒绝后表中记录应完好无损");
    }

    @Test
    void regression_C4_orderByAndPageQueryParamsRejectSqlInjection() {
        joya.of(Account.class).insert(new Account(1L, "Alice", 100, "alice@test.com"));

        assertThrows(IllegalArgumentException.class, () ->
                joya.of(Account.class).findAll(new OrderParam("id; DROP TABLE t_account--", OrderParam.OrderBy.ASC)));

        PageQueryParam badOrderParam = new PageQueryParam();
        badOrderParam.setOrderList(List.of(new OrderParam("1 OR 1=1", OrderParam.OrderBy.DESC)));
        assertThrows(IllegalArgumentException.class, () ->
                joya.from(Account.class)
                        .apply(PageQueryParams.of(badOrderParam))
                        .page(badOrderParam.toPageRequest()));

        PageQueryParam badFilterParam = new PageQueryParam();
        badFilterParam.addSearchParam("name; DROP TABLE t_account--", "Alice", FieldParam.Operator.EQ);
        assertThrows(IllegalArgumentException.class, () ->
                joya.from(Account.class)
                        .apply(PageQueryParams.of(badFilterParam))
                        .page(badFilterParam.toPageRequest()));

        PageQueryParam unknownFieldParam = new PageQueryParam();
        unknownFieldParam.addSearchParam("nonExistentColumn", "Alice", FieldParam.Operator.EQ);
        assertThrows(IllegalArgumentException.class, () ->
                joya.from(Account.class)
                        .apply(PageQueryParams.of(unknownFieldParam))
                        .page(unknownFieldParam.toPageRequest()));
    }

    // ==================== 2. P0 事务缺陷回归验证（T1-T7） ====================

    @Test
    void regression_T1_independentClientDoesNotHijackOtherTransactionConnection() {
        JoyaClient auditDirectClient = JoyaClient.create(dsAudit);

        joya.tx(tx -> {
            tx.of(Account.class).insert(new Account(1L, "PrimaryUser", 100, "p@test.com"));
            // 在主库事务块内调用另一个独立 JoyaClient，必须写进 dsAudit 而非串库写进 dsPrimary
            auditDirectClient.of(Account.class).insert(new Account(2L, "AuditUser", 200, "a@test.com"));
        });

        assertEquals(1L, joya.of(Account.class).count(), "dsPrimary 只应有 1 条记录");
        assertEquals(1L, auditDirectClient.of(Account.class).count(), "dsAudit 应有独立写入的 1 条记录");
    }

    @Test
    void regression_T6_savepointAndSavepointCallPartialRollbackKeepsOuterAndNestedSavepoints() {
        joya.tx(tx -> {
            tx.of(Account.class).insert(new Account(1L, "Outer", 100, null));

            int countInSp1 = tx.savepointCall(sp1 -> {
                sp1.of(Account.class).insert(new Account(2L, "Sp1Keep", 200, null));

                // 内层 savepoint 抛异常并被捕获：只回滚 sp2，保留 sp1 与 Outer
                assertThrows(IllegalStateException.class, () ->
                        sp1.savepoint(sp2 -> {
                            sp2.of(Account.class).insert(new Account(3L, "Sp2Discard", 300, null));
                            throw new IllegalStateException("abort sp2 only");
                        }));

                sp1.of(Account.class).insert(new Account(4L, "Sp1AfterSp2", 400, null));
                return (int) sp1.of(Account.class).count();
            });
            assertEquals(3, countInSp1);
        });

        List<Long> ids = joya.from(Account.class).orderByAsc(Account::getId).select(Account::getId).list();
        assertEquals(List.of(1L, 2L, 4L), ids, "仅 sp2 应回滚，Outer 与 sp1 应正常提交");
    }

    @Test
    void regression_T4_transactionalOpsWalkSqlInterceptorChain() {
        interceptedStatements.clear();

        joya.tx(tx -> {
            tx.of(Account.class).insert(new Account(1L, "TxUser", 100, null));
            tx.from(Account.class).where(w -> w.eq(Account::getId, 1L)).list();
            tx.sql("UPDATE t_account SET balance = ? WHERE id = ?", 500, 1L).update();
        });

        assertEquals(3, interceptedStatements.size(), "事务内的实体读写与原生 SQL 均必须经过同一套拦截器链");
    }

    @Test
    void regression_T5_transactionTimeoutRejectsSubsequentStatements() {
        assertThrows(TxTimeoutException.class, () ->
                joya.tx().timeout(Duration.ofMillis(10)).run(tx -> {
                    tx.of(Account.class).insert(new Account(1L, "SlowTx", 100, null));
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // 已超过 deadline，下一条语句必须抛 TxTimeoutException
                    tx.of(Account.class).count();
                }));

        assertEquals(0L, joya.of(Account.class).count(), "超时后事务必须自动回滚");
    }

    @Test
    void regression_T7_afterCommitAndAfterCompletionCallbacks() {
        AtomicBoolean afterCommitFired = new AtomicBoolean(false);
        AtomicReference<TxOutcome> outcomeRef = new AtomicReference<>();

        joya.tx(tx -> {
            tx.afterCommit(() -> afterCommitFired.set(true));
            tx.afterCompletion(outcomeRef::set);
            tx.of(Account.class).insert(new Account(1L, "CallbackUser", 100, null));
        });

        assertTrue(afterCommitFired.get(), "提交成功后应触发 afterCommit");
        assertEquals(TxOutcome.COMMITTED, outcomeRef.get());

        // 回滚场景：afterCommit 不触发，afterCompletion 收到 ROLLED_BACK
        afterCommitFired.set(false);
        outcomeRef.set(null);
        assertThrows(RuntimeException.class, () ->
                joya.tx(tx -> {
                    tx.afterCommit(() -> afterCommitFired.set(true));
                    tx.afterCompletion(outcomeRef::set);
                    throw new RuntimeException("rollback");
                }));

        assertFalse(afterCommitFired.get(), "回滚时不得触发 afterCommit");
        assertEquals(TxOutcome.ROLLED_BACK, outcomeRef.get());
    }

    // ==================== 3. 统一查询 DSL：9 种形态全覆盖 ====================

    @Test
    void queryDsl_fullEntity_partialEntity_singleColumn_wideDto_limitPageAndStream() {
        EntityOps<Account> accounts = joya.of(Account.class);
        accounts.insertAll(List.of(
                new Account(1L, "Alice", 100, "a@test.com"),
                new Account(2L, "Bob", 200, "b@test.com"),
                new Account(3L, "Charlie", 300, "c@test.com"),
                new Account(4L, "David", 400, "d@test.com")
        ));

        // 1. 完整实体：多次 .where() 自动以 AND 合并 + orderByDesc
        List<Account> filtered = joya.from(Account.class)
                .where(w -> w.gte(Account::getBalance, 200))
                .where(w -> w.lte(Account::getBalance, 300))
                .orderByDesc(Account::getBalance)
                .list();
        assertEquals(2, filtered.size());
        assertEquals("Charlie", filtered.get(0).getName());
        assertEquals("Bob", filtered.get(1).getName());

        // 2. 部分实体：select 挑列 + 按名字装回实体
        List<Account> partialAccounts = joya.from(Account.class)
                .where(w -> w.eq(Account::getId, 1L))
                .select(Account::getId, Account::getName)
                .list(Account.class);
        assertEquals(1, partialAccounts.size());
        assertEquals(1L, partialAccounts.get(0).getId());
        assertEquals("Alice", partialAccounts.get(0).getName());
        assertNull(partialAccounts.get(0).getBalance(), "未在 select 中挑出的字段应保持为 null");
        assertNull(partialAccounts.get(0).getEmail(), "未在 select 中挑出的字段应保持为 null");

        // 3. 单列直取：select(User::getName).list() / first() / one() / page()
        List<String> names = joya.from(Account.class)
                .orderByAsc(Account::getId)
                .select(Account::getName)
                .list();
        assertEquals(List.of("Alice", "Bob", "Charlie", "David"), names);

        Optional<String> richestName = joya.from(Account.class)
                .orderByDesc(Account::getBalance)
                .select(Account::getName)
                .first();
        assertEquals(Optional.of("David"), richestName);

        PageResult<String> namePage = joya.from(Account.class)
                .orderByAsc(Account::getId)
                .select(Account::getName)
                .page(PageRequest.of(0, 2));
        assertEquals(4L, namePage.getTotal());
        assertEquals(List.of("Alice", "Bob"), namePage.getData());

        // 4. 宽 DTO：不写 select，根据 DTO 字段自动推列
        List<AccountBrief> briefs = joya.from(Account.class)
                .orderByAsc(Account::getId)
                .list(AccountBrief.class);
        assertEquals(4, briefs.size());
        assertEquals(new AccountBrief(1L, "Alice", 100), briefs.get(0));

        // 5. first() vs one()
        assertThrows(IllegalStateException.class, () -> joya.from(Account.class).one(),
                "多于 1 行时 one() 必须抛 IllegalStateException");

        // 6. limit(offset, n) 不查总数直接截取
        List<Account> slice = joya.from(Account.class).orderByAsc(Account::getId).limit(1, 2).list();
        assertEquals(2, slice.size());
        assertEquals(2L, slice.get(0).getId());
        assertEquals(3L, slice.get(1).getId());

        // 7. 分页 page(PageRequest)
        PageResult<Account> page0 = joya.from(Account.class)
                .orderByAsc(Account::getId)
                .page(PageRequest.of(0, 2));
        assertEquals(4L, page0.getTotal());
        assertEquals(2, page0.getData().size());

        // 8. 动态 PageQueryParams 适配器分页
        PageQueryParam param = new PageQueryParam();
        param.page(0);
        param.pageSize(10);
        param.addSearchParam("balance", 250, FieldParam.Operator.GTE);
        param.setOrderList(List.of(new OrderParam("balance", OrderParam.OrderBy.DESC)));
        PageResult<Account> dynamicPage = joya.from(Account.class)
                .apply(PageQueryParams.of(param))
                .page(param.toPageRequest());
        assertEquals(2L, dynamicPage.getTotal());
        assertEquals("David", dynamicPage.getData().get(0).getName());

        // 9. Stream 读取
        try (Stream<Account> stream = joya.from(Account.class).orderByAsc(Account::getId).stream()) {
            assertEquals(4L, stream.count());
        }

        // 10. 批量 IN 删除 deleteByIds
        MutationResult delRes = accounts.deleteByIds(List.of(1L, 3L));
        assertEquals(2L, delRes.affectedRows());
        assertEquals(2L, accounts.count());
    }

    @Test
    void queryDsl_joins_partialEntities_positionalRecordMapping_groupByHaving_andAggregates() {
        joya.of(Account.class).insertAll(List.of(
                new Account(1L, "Alice", 500, "alice@test.com"),
                new Account(2L, "Bob", 300, "bob@test.com"),
                new Account(3L, "Charlie", 100, "charlie@test.com")
        ));
        joya.of(OrderRecord.class).insertAll(List.of(
                new OrderRecord(101L, 1L, "Keyboard", 120),
                new OrderRecord(102L, 1L, "Monitor", 280),
                new OrderRecord(103L, 2L, "Mouse", 60)
        ));

        interceptedStatements.clear();

        // 1. join 后不写 select：默认只查主表 OrderRecord 全部列
        List<OrderRecord> aliceOrders = joya.from(OrderRecord.class)
                .join(OrderRecord::getAccountId, Account::getId)
                .where(w -> w.eq(Account::getName, "Alice"))
                .orderByAsc(OrderRecord::getId)
                .list();
        assertEquals(2, aliceOrders.size());
        assertEquals("Keyboard", aliceOrders.get(0).getItemName());

        // 验证 SqlInterceptor 拿到了多表 TableRef 元数据
        assertFalse(interceptedStatements.isEmpty());
        Set<String> tableNames = interceptedStatements.get(0).metadata().tables().stream()
                .map(TableRef::name)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        assertTrue(tableNames.contains("t_account"), "拦截器元数据应包含 t_account: " + tableNames);
        assertTrue(tableNames.contains("t_order"), "拦截器元数据应包含 t_order: " + tableNames);

        // 2. join 后取主表部分字段：select(Order::getId, Order::getAmount).list(Order.class)
        List<OrderRecord> partialOrders = joya.from(OrderRecord.class)
                .join(OrderRecord::getAccountId, Account::getId)
                .where(w -> w.eq(Account::getName, "Bob"))
                .select(OrderRecord::getId, OrderRecord::getAmount)
                .list(OrderRecord.class);
        assertEquals(1, partialOrders.size());
        assertEquals(103L, partialOrders.get(0).getId());
        assertEquals(60, partialOrders.get(0).getAmount());
        assertNull(partialOrders.get(0).getItemName());

        // 3. join 后取关联表实体部分字段：select(Account::getId, Account::getName).list(Account.class)
        List<Account> orderOwners = joya.from(OrderRecord.class)
                .join(OrderRecord::getAccountId, Account::getId)
                .where(w -> w.eq(OrderRecord::getId, 101L))
                .select(Account::getId, Account::getName)
                .list(Account.class);
        assertEquals(1, orderOwners.size());
        assertEquals(1L, orderOwners.get(0).getId());
        assertEquals("Alice", orderOwners.get(0).getName());
        assertNull(orderOwners.get(0).getBalance());

        // 4. 跨表 DTO 按位置构造映射 + 分页（配合 *IfPresent / *IfNotBlank / *IfNotEmpty 动态忽略空条件）
        PageResult<AccountOrderView> viewPage = joya.from(OrderRecord.class)
                .leftJoin(OrderRecord::getAccountId, Account::getId)
                .where(w -> w
                        .eq(Account::getName, "Alice")
                        .eqIfPresent(Account::getEmail, null)              // null 显式忽略
                        .containsIfNotBlank(OrderRecord::getItemName, "  ") // 空白字符串显式忽略
                        .inIfNotEmpty(OrderRecord::getId, List.of())        // 空集合显式忽略
                )
                .orderByDesc(OrderRecord::getAmount)
                .select(OrderRecord::getId, OrderRecord::getAmount, Account::getName)
                .page(PageRequest.of(0, 10), AccountOrderView::new);
        assertEquals(2L, viewPage.getTotal());
        assertEquals(new AccountOrderView(102L, 280, "Alice"), viewPage.getData().get(0));
        assertEquals(new AccountOrderView(101L, 120, "Alice"), viewPage.getData().get(1));

        // 5. 分组统计 + having(Expr) + orderByDesc(Expr) + 分页 count 子查询
        PageResult<AccountOrderStat> groupPage = joya.from(OrderRecord.class)
                .join(OrderRecord::getAccountId, Account::getId)
                .groupBy(Account::getName)
                .having(h -> h.gte(count(), 1L))
                .orderByDesc(max(OrderRecord::getAmount))
                .select(Account::getName, max(OrderRecord::getAmount), count())
                .page(PageRequest.of(0, 10), AccountOrderStat::new);
        assertEquals(2L, groupPage.getTotal(), "GROUP BY 分页的 total 应为分组数（2 组）而非明细行数（3 行）");
        assertEquals(new AccountOrderStat("Alice", 280, 2L), groupPage.getData().get(0));
        assertEquals(new AccountOrderStat("Bob", 60, 1L), groupPage.getData().get(1));

        // 6. 统计值终结方法：count / exists / max / min / sum / avg / countDistinct
        assertEquals(2L, joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).count());
        assertTrue(joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).exists());
        assertFalse(joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 999L)).exists());

        assertEquals(Optional.of(280),
                joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).max(OrderRecord::getAmount));
        assertEquals(Optional.of(120),
                joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).min(OrderRecord::getAmount));
        assertEquals(Optional.of(400),
                joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).sum(OrderRecord::getAmount));
        assertEquals(200.0,
                joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 1L)).avg(OrderRecord::getAmount).orElseThrow(),
                0.001);
        assertEquals(Optional.of(2L),
                joya.from(OrderRecord.class).select(countDistinct(OrderRecord::getAccountId)).one());

        // 空结果集聚合返回 Optional.empty()
        assertEquals(Optional.empty(),
                joya.from(OrderRecord.class).where(w -> w.eq(OrderRecord::getAccountId, 999L)).max(OrderRecord::getAmount));
    }

    @Test
    void queryDsl_safetyGuards_recordOrderMismatch_duplicateLabel_andAmbiguousWideDto() {
        joya.of(Account.class).insert(new Account(1L, "Alice", 500, "alice@test.com"));
        joya.of(OrderRecord.class).insert(new OrderRecord(101L, 1L, "Keyboard", 120));

        // 1. Record 同类型列顺序颠倒：NameEmailPair(name, email) 却按 (getEmail, getName) 传入，必须报错
        IllegalStateException orderEx = assertThrows(IllegalStateException.class, () ->
                joya.from(Account.class)
                        .select(Account::getEmail, Account::getName)
                        .list(NameEmailPair::new));
        assertTrue(orderEx.getMessage().contains("NameEmailPair"),
                "异常信息应指出 Record 构造器参数顺序错位: " + orderEx.getMessage());

        // 顺序正确时正常返回
        List<NameEmailPair> pairs = joya.from(Account.class)
                .select(Account::getName, Account::getEmail)
                .list(NameEmailPair::new);
        assertEquals(List.of(new NameEmailPair("Alice", "alice@test.com")), pairs);

        // 2. list(X.class) 跨表同名列冲突：OrderRecord::getId 与 Account::getId 均叫 id，按名字映射必须报错
        assertThrows(IllegalStateException.class, () ->
                joya.from(OrderRecord.class)
                        .join(OrderRecord::getAccountId, Account::getId)
                        .select(OrderRecord::getId, Account::getId)
                        .list(Account.class));

        // 3. 宽 DTO 在联表下遇到同名字段 id 歧义，必须报错并提示使用 select(...).list(X::new)
        assertThrows(IllegalStateException.class, () ->
                joya.from(OrderRecord.class)
                        .join(OrderRecord::getAccountId, Account::getId)
                        .list(AmbiguousOrderDto.class));
    }

    @Test
    void whereCriterion_neTwoGetters_explicitIfPresentSkipping_andNullFailFast() {
        SqlStatement stmt = Query.of(Account.class)
                .where(w -> w
                        .ne(Account::getName, Account::getEmail)
                        .eqIfPresent(Account::getName, null)
                        .neIfPresent(Account::getEmail, null)
                        .containsIfNotBlank(Account::getName, "")
                        .notContainsIfNotBlank(Account::getName, "   ")
                        .inIfNotEmpty(Account::getId, null)
                        .notInIfNotEmpty(Account::getId, List.of())
                        .when(false, sub -> sub.eq(Account::getId, 999L))
                )
                .select(Account::getId, Account::getName)
                .toSqlStatement(Account.class);

        assertEquals("SELECT t0.id AS id, t0.name AS name FROM t_account t0 WHERE t0.name != t0.email", stmt.sql());
        assertTrue(stmt.parameters().isEmpty());

        // 标准比较方法遇到 null 或空集合必须 fail-fast 抛 IllegalArgumentException，防止 UPDATE/DELETE 误吞条件导致全表事故
        assertThrows(IllegalArgumentException.class, () -> WhereCriterion.create().eq(Account::getId, null));
        assertThrows(IllegalArgumentException.class, () -> WhereCriterion.create().ne(Account::getEmail, (Object) null));
        assertThrows(IllegalArgumentException.class, () -> WhereCriterion.create().in(Account::getId, List.of()));
        assertThrows(IllegalArgumentException.class, () -> WhereCriterion.create().notIn(Account::getId, (List<Long>) null));
    }

    @Test
    void dataOpsPolymorphism_sameMethodRunsAutoCommitOrInsideTx() {
        // 业务方法只依赖 DataOps 接口
        record TransferService() {
            void transfer(DataOps db, long fromId, long toId, int amount) {
                db.sql("UPDATE t_account SET balance = balance - ? WHERE id = ?", amount, fromId).update();
                db.sql("UPDATE t_account SET balance = balance + ? WHERE id = ?", amount, toId).update();
            }
        }
        TransferService service = new TransferService();

        joya.of(Account.class).insertAll(List.of(
                new Account(1L, "A", 500, null),
                new Account(2L, "B", 500, null)
        ));

        // 1. 直接传 joya（自动提交）
        service.transfer(joya, 1L, 2L, 100);
        assertEquals(400, joya.of(Account.class).findById(1L).orElseThrow().getBalance());
        assertEquals(600, joya.of(Account.class).findById(2L).orElseThrow().getBalance());

        // 2. 传 tx（事务中执行并回滚）
        assertThrows(RuntimeException.class, () ->
                joya.tx(tx -> {
                    service.transfer(tx, 1L, 2L, 200);
                    throw new RuntimeException("cancel transfer");
                }));

        assertEquals(400, joya.of(Account.class).findById(1L).orElseThrow().getBalance());
        assertEquals(600, joya.of(Account.class).findById(2L).orElseThrow().getBalance());
    }

    @Test
    void sqlOpsAndOfflineQueryStatementIntegration() {
        joya.sql("INSERT INTO t_account (id, name, balance, email) VALUES (?, ?, ?, ?)",
                10L, "NativeUser", 888, "native@test.com").insert();

        Optional<Integer> bal = joya.sql("SELECT balance FROM t_account WHERE id = ?", 10L).value(Integer.class);
        assertEquals(Optional.of(888), bal);

        List<Map<String, Object>> maps = joya.sql("SELECT id, name FROM t_account WHERE id = ?", 10L).listMap();
        assertEquals(1, maps.size());

        SqlStatement stmt = Query.of(Account.class)
                .where(w -> w.eq(Account::getId, 10L))
                .toSqlStatement();
        Account found = joya.sql(stmt).one(Account.class).orElseThrow();
        assertEquals("NativeUser", found.getName());
    }

    @Test
    void joyaMapperDelegatesToEntityOpsAndQueryDsl() {
        JoyaMapper<Account> mapper = new JoyaMapperImpl<>(joya, Account.class);
        Account acc = new Account(null, "MapperUser", 50, "m@test.com");
        Long id = (Long) mapper.save(acc);
        assertNotNull(id);

        assertTrue(mapper.existsById(id));
        assertEquals(1L, mapper.count());
        Account loaded = mapper.findByField(Account::getName, "MapperUser");
        assertNotNull(loaded);
        assertNull(mapper.findByField(Account::getName, "NotExist"));

        PageQueryParam pageParam = new PageQueryParam();
        pageParam.addSearchParam("name", "MapperUser", FieldParam.Operator.EQ);
        PageResult<Account> page = mapper.queryPage(pageParam);
        assertEquals(1L, page.getTotal());

        mapper.deleteById(id);
        assertEquals(0L, mapper.count());
    }
}
