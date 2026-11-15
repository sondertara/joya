package com.sondertara.joya.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.NamedSqlCompiler;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.mapper.JoyaMapper;
import com.sondertara.joya.core.naming.TableNameResolver;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.dynamic.PageQueryParams;
import com.sondertara.joya.core.query.pagination.PageQueryRequest;
import com.sondertara.joya.core.query.pagination.SortCondition;
import com.sondertara.joya.core.tx.IllegalTxStateException;
import com.sondertara.joya.core.tx.TxException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心 API 易用性与派生配置视图回归/集成测试：
 * 覆盖 {@code withSettings}、{@code JoyaSettings.toBuilder()}、{@code PageResult.map} + Jackson、
 * {@code SortCondition(IGetter)}、{@code JoyaMapper} 增量方法、{@code WhereCriterion} 扩展与 {@code SqlOps} 具名参数。
 */
class ApiUsabilityAndSettingsTest {

    @Entity
    @Table(name = "t_api_account")
    public static class AccountItem {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "user_name")
        private String userName;

        @Column(name = "age")
        private Integer age;

        @Column(name = "score")
        private Integer score;

        public AccountItem() {
        }

        public AccountItem(Long id, String userName, Integer age, Integer score) {
            this.id = id;
            this.userName = userName;
            this.age = age;
            this.score = score;
        }

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

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }

        public Integer getScore() {
            return score;
        }

        public void setScore(Integer score) {
            this.score = score;
        }
    }

    public interface AccountMapper extends JoyaMapper<AccountItem> {
    }

    public static final class ParamPojo {
        private final String name;
        private Integer minAge;

        public ParamPojo(String name, Integer minAge) {
            this.name = name;
            this.minAge = minAge;
        }

        public String getName() {
            return name;
        }

        public Integer getMinAge() {
            return minAge;
        }

        public void setMinAge(Integer minAge) {
            this.minAge = minAge;
        }
    }

    public record ParamRecord(String prefix, List<Long> ids) {
    }

    private JdbcDataSource rawDataSource;
    private DataSource spyDataSource;
    private final List<Integer> recordedTimeouts = new ArrayList<>();
    private final List<SqlStatement> executedStatements = new ArrayList<>();
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        rawDataSource = new JdbcDataSource();
        rawDataSource.setURL("jdbc:h2:mem:api_usability_test;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        rawDataSource.setUser("sa");
        rawDataSource.setPassword("");

        recordedTimeouts.clear();
        executedStatements.clear();

        spyDataSource = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object res = invokeUnwrapped(method, rawDataSource, args);
                    if ("getConnection".equals(method.getName()) && res instanceof Connection conn) {
                        return wrapConnection(conn);
                    }
                    return res;
                });

        joya = JoyaClient.builder(spyDataSource)
                .settings(s -> s.withQueryTimeoutSeconds(30).withBatchSize(10))
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        executedStatements.add(ctx.statement());
                    }
                })
                .build();

        joya.sql("""
                DROP TABLE IF EXISTS t_api_account;
                DROP TABLE IF EXISTS t_api_account_202604;
                CREATE TABLE t_api_account (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64) NOT NULL,
                    age INT,
                    score INT DEFAULT 100
                );
                CREATE TABLE t_api_account_202604 (
                    id BIGINT PRIMARY KEY,
                    user_name VARCHAR(64) NOT NULL,
                    age INT,
                    score INT DEFAULT 200
                );
                """).update();
        recordedTimeouts.clear();
        executedStatements.clear();
    }

    private Connection wrapConnection(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object res = invokeUnwrapped(method, target, args);
                    if ("prepareStatement".equals(method.getName()) && res instanceof PreparedStatement ps) {
                        return wrapPreparedStatement(ps);
                    }
                    return res;
                });
    }

    private PreparedStatement wrapPreparedStatement(PreparedStatement target) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setQueryTimeout".equals(method.getName()) && args != null && args.length == 1) {
                        recordedTimeouts.add((Integer) args[0]);
                    }
                    return invokeUnwrapped(method, target, args);
                });
    }

    private static Object invokeUnwrapped(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    @Test
    void withSettings_supportsDynamicTableRoutingBatchSizeAndTimeoutClampingWithoutMutatingSource() {
        TableNameResolver shardResolver = (schema, table) -> table + "_202604";
        JoyaClient sharded = joya.withSettings(s -> s.toBuilder()
                .tableNameResolver(shardResolver)
                .batchSize(2)
                .queryTimeoutSeconds(5)
                .build());

        sharded.of(AccountItem.class).insertAll(List.of(
                new AccountItem(1L, "S1", 20, 10),
                new AccountItem(2L, "S2", 21, 20),
                new AccountItem(3L, "S3", 22, 30)));
        assertEquals(3L, sharded.from(AccountItem.class).count());
        assertEquals(0L, joya.from(AccountItem.class).count(), "原客户端不得受派生视图分表策略污染");

        sharded.of(AccountItem.class).update()
                .set(AccountItem::getScore, 99)
                .where(w -> w.eq(AccountItem::getId, 1L))
                .execute();
        assertEquals(99, sharded.of(AccountItem.class).findById(1L).orElseThrow().getScore());

        // 校验 queryTimeout 覆写与清零
        recordedTimeouts.clear();
        joya.from(AccountItem.class).list();
        assertEquals(List.of(30), recordedTimeouts);

        recordedTimeouts.clear();
        sharded.from(AccountItem.class).list();
        assertEquals(List.of(5), recordedTimeouts);

        recordedTimeouts.clear();
        JoyaClient noTimeout = joya.withSettings(s -> s.withExecutionOptions(
                ExecutionOptions.DEFAULT.withQueryTimeoutSeconds(0)));
        noTimeout.from(AccountItem.class).list();
        assertTrue(recordedTimeouts.isEmpty(), "显式将 timeout 置为 0 应清除原客户端默认超时");

        // 事务内 withSettings：遵循 deadline 上界钳位且继承 Tx 状态/线程守卫
        AtomicReference<DataOps> escapedView = new AtomicReference<>();
        joya.tx().timeoutSeconds(10).run(tx -> {
            recordedTimeouts.clear();
            tx.from(AccountItem.class).list();
            int baseDeadline = recordedTimeouts.get(0);
            assertTrue(baseDeadline >= 1 && baseDeadline <= 10);

            recordedTimeouts.clear();
            tx.withSettings(s -> s.toBuilder().queryTimeoutSeconds(4).build()).from(AccountItem.class).list();
            assertEquals(List.of(4), recordedTimeouts);

            recordedTimeouts.clear();
            tx.withSettings(s -> s.toBuilder().queryTimeoutSeconds(25).build()).from(AccountItem.class).list();
            int clamped = recordedTimeouts.get(0);
            assertTrue(clamped >= 1 && clamped <= 10, "事务内不得突破外层 deadline 上限: " + clamped);

            recordedTimeouts.clear();
            DataOps txNoOverride = tx.withSettings(s -> s.toBuilder().queryTimeoutSeconds(0).build());
            txNoOverride.from(AccountItem.class).list();
            assertFalse(recordedTimeouts.isEmpty());
            assertTrue(recordedTimeouts.get(0) <= 10, "即使派生视图设 timeout=0，仍受活动事务 deadline 约束");

            DataOps txShard = tx.withSettings(s -> s.withTableNameResolver(shardResolver).withBatchSize(1));
            txShard.of(AccountItem.class).insert(new AccountItem(4L, "S4", 24, 40));

            // 跨线程不可逃逸访问
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> crossThreadError = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    txShard.from(AccountItem.class).count();
                } catch (Throwable error) {
                    crossThreadError.set(error);
                } finally {
                    done.countDown();
                }
            });
            t.start();
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertTrue(crossThreadError.get() instanceof TxException);
            assertTrue(crossThreadError.get().getMessage().contains("invariant I9"));

            escapedView.set(txShard);
        });

        assertEquals(4L, sharded.from(AccountItem.class).count());
        assertThrows(IllegalTxStateException.class, () -> escapedView.get().from(AccountItem.class).count());
    }

    @Test
    void pageResultMap_preservesSliceState_andJacksonOmitsIgnoredAliases() throws Exception {
        PageResult<AccountItem> page = PageResult.of(
                List.of(new AccountItem(1L, "Alice", 15, 80), new AccountItem(2L, "Bob", 20, 90)),
                -1L, 2, 2, true);
        PageResult<String> mapped = page.map(item -> item.getUserName() + ":" + (item.getAge() * 2));

        assertEquals(List.of("Alice:30", "Bob:40"), mapped.items());
        assertEquals(2, mapped.pageNum());
        assertEquals(2, mapped.pageSize());
        assertEquals(-1L, mapped.total());
        assertFalse(mapped.hasTotal());
        assertTrue(mapped.hasNext(), "map() 后应保留免 COUNT 切片模式的 hasNextOverride");

        PageResult<AccountItem> empty = PageResult.empty(1, 10);
        PageResult<String> mappedEmpty = empty.map(AccountItem::getUserName);
        assertEquals(0L, mappedEmpty.total());
        assertTrue(mappedEmpty.isEmpty());
        assertFalse(mappedEmpty.hasNext());

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(mapped));
        assertTrue(json.has("items"));
        assertTrue(json.has("pageNum"));
        assertFalse(json.has("data"), "@JsonIgnore 应避免 data 与 items 重复输出");
        assertFalse(json.has("currentPage"), "@JsonIgnore 应避免 currentPage 与 pageNum 混淆");
        assertFalse(json.has("empty"), "@JsonIgnore 应避免输出多余 empty 属性");
    }

    @Test
    void sortConditionGettersAndMapperIncrementalApis_workEndToEnd() {
        AccountMapper mapper = joya.mapper(AccountMapper.class);

        // insertAllIgnoreNull：两条非空列不同的记录各自动态选列（第二条依赖数据库 DEFAULT 100）
        int inserted = mapper.insertAllIgnoreNull(List.of(
                new AccountItem(1L, "Alice", 22, 60),
                new AccountItem(2L, "Bob", 25, null)));
        assertEquals(2, inserted);
        assertEquals(100, mapper.findById(2L).orElseThrow().getScore());

        // saveAllIgnoreNull：1 条更新（score 传 null 保持 60 不覆盖），1 条新增（score 传 null 用默认 100）
        mapper.saveAllIgnoreNull(List.of(
                new AccountItem(1L, "AliceV2", null, null),
                new AccountItem(3L, "Carol", 19, null)));
        AccountItem one = mapper.findById(1L).orElseThrow();
        assertEquals("AliceV2", one.getUserName());
        assertEquals(22, one.getAge(), "saveAllIgnoreNull 不得把 null 覆盖既有非空列");
        assertEquals(60, one.getScore());
        assertEquals(100, mapper.findById(3L).orElseThrow().getScore());

        // updateByCondition：正常更新 + 空 WHERE 防护
        int updated = mapper.updateByCondition(u -> u.set(AccountItem::getScore, 500)
                .where(w -> w.eq(AccountItem::getUserName, "Carol")));
        assertEquals(1, updated);
        assertEquals(500, mapper.findById(3L).orElseThrow().getScore());
        assertThrows(IllegalStateException.class,
                () -> mapper.updateByCondition(u -> u.set(AccountItem::getScore, 0)));

        // SortCondition.asc(IGetter) / desc(IGetter) + PageQueryParams
        PageQueryRequest req = new PageQueryRequest(1, 10);
        req.setSorts(List.of(SortCondition.desc(AccountItem::getScore), SortCondition.asc(AccountItem::getId)));
        PageResult<AccountItem> sorted = joya.from(AccountItem.class)
                .apply(PageQueryParams.of(req))
                .page(req.toPageRequest());
        assertEquals(List.of(3L, 2L, 1L), sorted.items().stream().map(AccountItem::getId).toList());
    }

    @Test
    void whereCriterionExtensions_respectRangeBlankAndDeMorganNotInChunking() {
        joya.of(AccountItem.class).insertAll(List.of(
                new AccountItem(1L, "Alice", 10, 100),
                new AccountItem(2L, "Bob", 20, 200),
                new AccountItem(3L, "Charlie", 30, 300),
                new AccountItem(4L, "Diana", 40, 400)));

        // betweenIfPresent 保持原严格语义：任一端为 null 跳过
        assertEquals(4, joya.from(AccountItem.class)
                .where(w -> w.betweenIfPresent(AccountItem::getAge, 25, null))
                .count());

        // rangeIfPresent 降级语义：两端均非空 -> BETWEEN；左非空 -> >=；右非空 -> <=；全空 -> 跳过
        assertEquals(List.of(2L, 3L), joya.from(AccountItem.class)
                .where(w -> w.rangeIfPresent(AccountItem::getAge, 20, 30))
                .orderByAsc(AccountItem::getId)
                .select(AccountItem::getId).list());
        assertEquals(List.of(3L, 4L), joya.from(AccountItem.class)
                .where(w -> w.rangeIfPresent(AccountItem::getAge, 25, null))
                .orderByAsc(AccountItem::getId)
                .select(AccountItem::getId).list());
        assertEquals(List.of(1L, 2L), joya.from(AccountItem.class)
                .where(w -> w.rangeIfPresent(AccountItem::getAge, null, 20))
                .orderByAsc(AccountItem::getId)
                .select(AccountItem::getId).list());
        assertEquals(4, joya.from(AccountItem.class)
                .where(w -> w.rangeIfPresent(AccountItem::getAge, null, null))
                .count());

        // *IfNotBlank 忽略空白串，非空白生效
        assertEquals(List.of(1L, 2L, 4L), joya.from(AccountItem.class)
                .where(w -> w.likeIfNotBlank(AccountItem::getUserName, "   ")
                        .notLikeIfNotBlank(AccountItem::getUserName, "%arl%")
                        .notStartsWithIfNotBlank(AccountItem::getUserName, "  ")
                        .notEndsWithIfNotBlank(AccountItem::getUserName, "\t"))
                .orderByAsc(AccountItem::getId)
                .select(AccountItem::getId).list());
        assertEquals(List.of(2L), joya.from(AccountItem.class)
                .where(w -> w.notStartsWithIfNotBlank(AccountItem::getUserName, "A")
                        .notEndsWithIfNotBlank(AccountItem::getUserName, "e")
                        .notEndsWithIfNotBlank(AccountItem::getUserName, "na"))
                .select(AccountItem::getId).list());

        // inChunkedIfNotEmpty / notInChunkedIfNotEmpty 空集合跳过；> 1000 走分片且 notInChunked 使用 AND（德摩根律）
        List<Long> largeIds = new ArrayList<>(1200);
        for (long i = 10; i < 1205; i++) {
            largeIds.add(i);
        }
        largeIds.add(1L);
        largeIds.add(3L);

        executedStatements.clear();
        List<Long> excluded = joya.from(AccountItem.class)
                .where(w -> w.inChunkedIfNotEmpty(AccountItem::getId, List.of())
                        .notInChunkedIfNotEmpty(AccountItem::getId, null)
                        .notInChunked(AccountItem::getId, largeIds))
                .orderByAsc(AccountItem::getId)
                .select(AccountItem::getId)
                .list();
        assertEquals(List.of(2L, 4L), excluded, "超过 1000 个元素时 notInChunked 必须用 AND 连接分片才等价");
        String sql = executedStatements.get(executedStatements.size() - 1).sql();
        assertTrue(sql.contains("NOT IN (") && sql.contains(") AND t0.id NOT IN ("), sql);
    }

    @Test
    void namedSqlCompilerAndSqlOpsBinding_handleCollectionsBeansLexicalSafetyAndRejectInvalidMixing() {
        joya.of(AccountItem.class).insertAll(List.of(
                new AccountItem(1L, "Alice", 20, 80),
                new AccountItem(2L, "Bob", 30, 90),
                new AccountItem(3L, "Carol", 40, 100)));

        // bind + 同名参数复用 + 集合展开 + null 绑定
        List<AccountItem> hit = joya.sql("""
                SELECT * FROM t_api_account
                WHERE id IN (:ids)
                  AND (:minAge IS NULL OR age >= :minAge)
                  AND user_name <> :excludedName
                ORDER BY id ASC
                """)
                .bind("ids", List.of(1L, 2L, 3L))
                .bind("minAge", 25)
                .bind("excludedName", "Nobody")
                .list(AccountItem.class);
        assertEquals(List.of(2L, 3L), hit.stream().map(AccountItem::getId).toList());

        List<Long> nullThresholdIds = joya.sql("SELECT id FROM t_api_account WHERE :minAge IS NULL OR age >= :minAge ORDER BY id")
                .bind("minAge", null)
                .list(Long.class);
        assertEquals(List.of(1L, 2L, 3L), nullThresholdIds);

        // bindBean (POJO + Record)
        List<String> pojoNames = joya.sql("SELECT user_name FROM t_api_account WHERE user_name = :name OR age >= :minAge ORDER BY id")
                .bindBean(new ParamPojo("Alice", 40))
                .list(String.class);
        assertEquals(List.of("Alice", "Carol"), pojoNames);

        List<String> recordNames = joya.sql("SELECT user_name FROM t_api_account WHERE id IN (:ids) AND user_name LIKE :prefix ORDER BY id")
                .bindBean(new ParamRecord("B%", List.of(1L, 2L, 3L)))
                .list(String.class);
        assertEquals(List.of("Bob"), recordNames);

        // bindMap 覆盖 + DML INSERT/UPDATE
        Map<String, Object> insertParams = new LinkedHashMap<>();
        insertParams.put("id", 4L);
        insertParams.put("name", "Dave");
        insertParams.put("age", 35);
        int affected = joya.sql("INSERT INTO t_api_account (id, user_name, age) VALUES (:id, :name, :age)")
                .bindMap(insertParams)
                .update();
        assertEquals(1, affected);
        assertEquals("Dave", joya.of(AccountItem.class).findById(4L).orElseThrow().getUserName());

        // 词法豁免：::type、:=、字符串字面量、注释、$tag$
        SqlStatement compiled = NamedSqlCompiler.compile(
                """
                SELECT ':ignored' /* :in_comment */ -- :line_comment
                       , $tag$:in_dollar$tag$, col::bigint, @v := :realVal
                FROM t_tbl WHERE k = :realVal
                """,
                Map.of("realVal", 42),
                Operation.SELECT,
                DbType.postgresql);
        assertEquals(List.of(42, 42), compiled.args());
        assertTrue(compiled.sql().contains("col::bigint"));
        assertTrue(compiled.sql().contains("@v := ?"));
        assertTrue(compiled.sql().contains("':ignored'"));
        assertTrue(compiled.sql().contains("$tag$:in_dollar$tag$"));

        // 失败边界校验
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id = :id").bind("other", 1L).list(AccountItem.class));
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id = ? AND user_name = :name")
                        .bind("name", "Alice").list(AccountItem.class));
        assertThrows(IllegalStateException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id = ?", 1L).bind("id", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id IN (:ids)").bind("ids", List.of()).listMap());
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id = :id").bindBean(Map.of("id", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id IN (:ids)").bindBean(List.of(1L, 2L)));
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE user_name = :name").bindBean("Alice"));
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_api_account WHERE id = :id").bindBean(new long[]{1L, 2L}));

        List<Long> overLimit = new ArrayList<>(1001);
        for (long i = 0; i < 1001; i++) {
            overLimit.add(i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> NamedSqlCompiler.compile("SELECT * FROM t WHERE id IN (:ids)", Map.of("ids", overLimit),
                        Operation.SELECT, DbType.h2));
    }
}
