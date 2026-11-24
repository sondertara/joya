package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.executor.Executor;
import com.sondertara.joya.core.kernel.executor.JdbcExecutor;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.exceptions.OptimisticLockException;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 专项回归：标准 JPA {@code @Convert}（双向 {@link AttributeConverter}）、JSR-310 {@link Instant}，
 * 以及 {@code @Version} 乐观锁全生命周期与 {@code batchExecuteExact} 精确行计数批执行。
 */
class ConvertAndVersionIntegrationTest {

    private JoyaClient joya;
    private JdbcDataSource dataSource;

    enum DocStatus {
        DRAFT(10),
        PUBLISHED(20),
        ARCHIVED(30);

        final int code;

        DocStatus(int code) {
            this.code = code;
        }

        static DocStatus fromCode(int code) {
            for (DocStatus value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown code: " + code);
        }
    }

    public static class DocStatusCodeConverter implements AttributeConverter<DocStatus, Integer> {
        @Override
        public Integer convertToDatabaseColumn(DocStatus attribute) {
            return attribute == null ? null : attribute.code;
        }

        @Override
        public DocStatus convertToEntityAttribute(Integer dbData) {
            return dbData == null ? null : DocStatus.fromCode(dbData);
        }
    }

    public static class StringListCsvConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            if (attribute == null) {
                return "__NULL__";
            }
            return String.join(",", attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty() || "__NULL__".equals(dbData)) {
                return List.of();
            }
            return Arrays.asList(dbData.split(","));
        }
    }

    @Entity
    @Table(name = "t_conv_doc")
    public static class ConvDoc {
        @Id
        private Long id;

        private String title;

        @Convert(converter = DocStatusCodeConverter.class)
        private DocStatus status;

        @Convert(converter = StringListCsvConverter.class)
        private List<String> tags;

        @Column(name = "created_at")
        private Instant createdAt;

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

        public DocStatus getStatus() {
            return status;
        }

        public void setStatus(DocStatus status) {
            this.status = status;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    public record ConvDocRecord(
            Long id,
            String title,
            @Convert(converter = DocStatusCodeConverter.class) DocStatus status,
            @Convert(converter = StringListCsvConverter.class) List<String> tags
    ) {
    }

    public record PositionalSummary(Long id, DocStatus status, List<String> tags) {
    }

    /** 未重复标注 @Convert 的普通 Record 投影 DTO：应自动继承源实体的 ColumnConverter。 */
    public record PlainDocBrief(Long id, DocStatus status, List<String> tags) {
    }

    /** 未重复标注 @Convert 的普通 JavaBean 投影 DTO（含非标量 List<String> 字段）。 */
    public static class PlainDocBriefBean {
        private Long id;
        private DocStatus status;
        private List<String> tags;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public DocStatus getStatus() {
            return status;
        }

        public void setStatus(DocStatus status) {
            this.status = status;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    /** @Convert 标注在 Getter 方法上（属性访问风格）：验证写路径与读路径一致生效。 */
    @Entity
    @Table(name = "t_conv_doc")
    public static class GetterConvertedDoc {
        @Id
        private Long id;
        private String title;
        private DocStatus status;
        private List<String> tags;

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

        @Convert(converter = DocStatusCodeConverter.class)
        public DocStatus getStatus() {
            return status;
        }

        public void setStatus(DocStatus status) {
            this.status = status;
        }

        @Convert(converter = StringListCsvConverter.class)
        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }
    }

    @Entity
    @Table(name = "t_versioned_order")
    public static class VersionedOrder {
        @Id
        private Long id;

        private String name;

        private Integer amount;

        @Version
        private Long version;

        public VersionedOrder() {
        }

        public VersionedOrder(Long id, String name, Integer amount, Long version) {
            this.id = id;
            this.name = name;
            this.amount = amount;
            this.version = version;
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

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    @Entity
    @Table(name = "t_short_version")
    public static class ShortVersionEntity {
        @Id
        private Long id;
        private String label;
        @Version
        private Short ver;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Short getVer() {
            return ver;
        }

        public void setVer(Short ver) {
            this.ver = ver;
        }
    }

    // ===== 非法元数据测试实体 =====

    public static class NotAConverter {
    }

    public static class NoDefaultCtorConverter implements AttributeConverter<String, String> {
        public NoDefaultCtorConverter(String required) {
        }

        @Override
        public String convertToDatabaseColumn(String attribute) {
            return attribute;
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return dbData;
        }
    }

    @Entity
    @Table(name = "t_bad_conv_1")
    public static class InvalidConverterInterfaceEntity {
        @Id
        private Long id;
        @Convert(converter = NotAConverter.class)
        private String data;
    }

    @Entity
    @Table(name = "t_bad_conv_2")
    public static class MissingNoArgCtorConverterEntity {
        @Id
        private Long id;
        @Convert(converter = NoDefaultCtorConverter.class)
        private String data;
    }

    @Entity
    @Table(name = "t_bad_ver_type")
    public static class BadVersionTypeEntity {
        @Id
        private Long id;
        @Version
        private String ver;
    }

    @Entity
    @Table(name = "t_dup_ver")
    public static class DuplicateVersionEntity {
        @Id
        private Long id;
        @Version
        private Integer v1;
        @Version
        private Long v2;
    }

    @Entity
    @Table(name = "t_id_ver")
    public static class IdAndVersionEntity {
        @Id
        @Version
        private Long id;
        private String name;
    }

    @Entity
    @Table(name = "t_gen_ver")
    public static class GeneratedVersionEntity {
        @Id
        private Long id;
        @Version
        @GeneratedValue
        private Long ver;
    }

    @Entity
    @Table(name = "t_non_upd_ver")
    public static class NonUpdatableVersionEntity {
        @Id
        private Long id;
        @Version
        @Column(updatable = false)
        private Long ver;
    }

    @Entity
    @Table(name = "t_conv_ver")
    public static class ConvertedVersionEntity {
        @Id
        private Long id;
        @Version
        @Convert(converter = DocStatusCodeConverter.class)
        private Integer ver;
    }

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conv_ver_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        joya = JoyaClient.create(dataSource);
        joya.sql("""
                CREATE TABLE t_conv_doc (
                    id BIGINT PRIMARY KEY,
                    title VARCHAR(64),
                    status INT,
                    tags VARCHAR(255),
                    created_at TIMESTAMP
                )""").update();
        joya.sql("""
                CREATE TABLE t_versioned_order (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(64),
                    amount INT,
                    version BIGINT NOT NULL
                )""").update();
        joya.sql("""
                CREATE TABLE t_short_version (
                    id BIGINT PRIMARY KEY,
                    label VARCHAR(64),
                    ver SMALLINT NOT NULL
                )""").update();
    }

    // ==================== @Convert & Instant 回归 ====================

    @Test
    void convert_endToEndCrudQueriesProjectionsAndInstantRoundTrip() {
        Instant t1 = Instant.parse("2026-01-15T10:30:00.123Z");
        Instant t2 = Instant.parse("2026-02-20T18:45:00.456Z");

        ConvDoc d1 = new ConvDoc();
        d1.setId(1L);
        d1.setTitle("Alpha");
        d1.setStatus(DocStatus.PUBLISHED);
        d1.setTags(List.of("java", "orm", "joya"));
        d1.setCreatedAt(t1);
        joya.of(ConvDoc.class).insert(d1);

        // 验证数据库底层列确实存入转换后的标量值（20 与 "java,orm,joya"）
        assertEquals(20, joya.sql("SELECT status FROM t_conv_doc WHERE id = 1").value(Integer.class).orElseThrow());
        assertEquals("java,orm,joya",
                joya.sql("SELECT tags FROM t_conv_doc WHERE id = 1").value(String.class).orElseThrow());

        // insertIgnoreNull：原始属性为 null 时必须在调用 converter 之前跳过（即使 converter 会把 null 转成 "__NULL__"）
        ConvDoc d2 = new ConvDoc();
        d2.setId(2L);
        d2.setTitle("Beta");
        d2.setStatus(DocStatus.DRAFT);
        d2.setTags(null);
        d2.setCreatedAt(t2);
        joya.of(ConvDoc.class).insertIgnoreNull(d2);
        assertNull(joya.sql("SELECT tags FROM t_conv_doc WHERE id = 2").value(String.class).orElse(null),
                "insertIgnoreNull 应在转换前跳过 null 属性");

        // BeanRowMapper 实体回读 + Instant 精确往返
        ConvDoc loaded = joya.of(ConvDoc.class).findById(1L).orElseThrow();
        assertEquals(DocStatus.PUBLISHED, loaded.getStatus());
        assertEquals(List.of("java", "orm", "joya"), loaded.getTags());
        assertEquals(t1, loaded.getCreatedAt());

        // Java Record 投影 + @Convert
        ConvDocRecord rec = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 1L))
                .one(ConvDocRecord.class)
                .orElseThrow();
        assertEquals(DocStatus.PUBLISHED, rec.status());
        assertEquals(List.of("java", "orm", "joya"), rec.tags());

        // WhereCriterion：枚举属性条件、IN 条件、以及弱类型字符串列透传数据库码值
        List<ConvDoc> byEnum = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getStatus, DocStatus.PUBLISHED))
                .list();
        assertEquals(1, byEnum.size());
        assertEquals(1L, byEnum.get(0).getId());

        List<ConvDoc> byIn = joya.from(ConvDoc.class)
                .where(w -> w.in(ConvDoc::getStatus, List.of(DocStatus.DRAFT, DocStatus.PUBLISHED)))
                .orderByAsc(ConvDoc::getId)
                .list();
        assertEquals(2, byIn.size());

        List<ConvDoc> byRawCode = joya.from(ConvDoc.class)
                .where(w -> w.eq("status", 20))
                .list();
        assertEquals(1, byRawCode.size());
        assertEquals(1L, byRawCode.get(0).getId());

        // Instant 条件查询
        List<ConvDoc> afterT1 = joya.from(ConvDoc.class)
                .where(w -> w.gt(ConvDoc::getCreatedAt, t1))
                .list();
        assertEquals(1, afterT1.size());
        assertEquals(2L, afterT1.get(0).getId());

        // Select1 单列直取与 Select3 按位置 Record 投影
        List<DocStatus> statuses = joya.from(ConvDoc.class)
                .orderByAsc(ConvDoc::getId)
                .select(ConvDoc::getStatus)
                .list();
        assertEquals(List.of(DocStatus.PUBLISHED, DocStatus.DRAFT), statuses);

        List<List<String>> allTags = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 1L))
                .select(ConvDoc::getTags)
                .list();
        assertEquals(List.of(List.of("java", "orm", "joya")), allTags);

        PositionalSummary summary = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 1L))
                .select(ConvDoc::getId, ConvDoc::getStatus, ConvDoc::getTags)
                .one(PositionalSummary::new)
                .orElseThrow();
        assertEquals(new PositionalSummary(1L, DocStatus.PUBLISHED, List.of("java", "orm", "joya")), summary);

        // EntityUpdate + patch + updateAll + save + EntityInsert 覆盖全写路径
        joya.of(ConvDoc.class).update()
                .set(ConvDoc::getStatus, DocStatus.ARCHIVED)
                .set(ConvDoc::getTags, List.of("done"))
                .where(w -> w.eq(ConvDoc::getId, 1L))
                .execute();
        ConvDoc afterUpdate = joya.of(ConvDoc.class).findById(1L).orElseThrow();
        assertEquals(DocStatus.ARCHIVED, afterUpdate.getStatus());
        assertEquals(List.of("done"), afterUpdate.getTags());

        joya.of(ConvDoc.class).insert()
                .value(ConvDoc::getId, 3L)
                .value(ConvDoc::getTitle, "Gamma")
                .value("status", 30)
                .value(ConvDoc::getTags, List.of("a", "b"))
                .execute();
        ConvDoc d3 = joya.of(ConvDoc.class).findById(3L).orElseThrow();
        assertEquals(DocStatus.ARCHIVED, d3.getStatus());
        assertEquals(List.of("a", "b"), d3.getTags());

        d2.setStatus(DocStatus.PUBLISHED);
        d2.setTags(List.of("v2"));
        d3.setStatus(DocStatus.DRAFT);
        d3.setTags(List.of("v3"));
        joya.of(ConvDoc.class).updateAll(List.of(d2, d3));
        assertEquals(DocStatus.PUBLISHED, joya.of(ConvDoc.class).findById(2L).orElseThrow().getStatus());
        assertEquals(List.of("v3"), joya.of(ConvDoc.class).findById(3L).orElseThrow().getTags());

        d3.setStatus(DocStatus.ARCHIVED);
        joya.of(ConvDoc.class).save(d3);
        assertEquals(DocStatus.ARCHIVED, joya.of(ConvDoc.class).findById(3L).orElseThrow().getStatus());

        // 未重复标注 @Convert 的普通 Record / JavaBean DTO（auto 推列与 named select 均应自动继承源实体转换器）
        PlainDocBrief autoRec = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 3L))
                .one(PlainDocBrief.class)
                .orElseThrow();
        assertEquals(new PlainDocBrief(3L, DocStatus.ARCHIVED, List.of("v3")), autoRec);

        PlainDocBriefBean autoBean = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 3L))
                .one(PlainDocBriefBean.class)
                .orElseThrow();
        assertEquals(3L, autoBean.getId());
        assertEquals(DocStatus.ARCHIVED, autoBean.getStatus());
        assertEquals(List.of("v3"), autoBean.getTags());

        PlainDocBrief namedRec = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 3L))
                .select(ConvDoc::getId, ConvDoc::getStatus, ConvDoc::getTags)
                .one(PlainDocBrief.class)
                .orElseThrow();
        assertEquals(new PlainDocBrief(3L, DocStatus.ARCHIVED, List.of("v3")), namedRec);

        PlainDocBriefBean namedBean = joya.from(ConvDoc.class)
                .where(w -> w.eq(ConvDoc::getId, 3L))
                .select(ConvDoc::getId, ConvDoc::getStatus, ConvDoc::getTags)
                .one(PlainDocBriefBean.class)
                .orElseThrow();
        assertEquals(DocStatus.ARCHIVED, namedBean.getStatus());
        assertEquals(List.of("v3"), namedBean.getTags());

        // MAX / MIN 聚合在 @Convert 列上应回转为实体属性类型（此时库中状态为 ARCHIVED(30) 与 PUBLISHED(20)）
        assertEquals(DocStatus.ARCHIVED, joya.from(ConvDoc.class).max(ConvDoc::getStatus).orElseThrow());
        assertEquals(DocStatus.PUBLISHED, joya.from(ConvDoc.class).min(ConvDoc::getStatus).orElseThrow());

        // Getter 上标注 @Convert：写路径（LocalTableMetaCache）与读路径（BeanRowMapper）统一生效
        GetterConvertedDoc gd = new GetterConvertedDoc();
        gd.setId(4L);
        gd.setTitle("GetterStyle");
        gd.setStatus(DocStatus.DRAFT);
        gd.setTags(List.of("getter", "unified"));
        joya.of(GetterConvertedDoc.class).insert(gd);
        assertEquals(10, joya.sql("SELECT status FROM t_conv_doc WHERE id = 4").value(Integer.class).orElseThrow());
        GetterConvertedDoc loadedGd = joya.from(GetterConvertedDoc.class)
                .where(w -> w.eq(GetterConvertedDoc::getStatus, DocStatus.DRAFT))
                .one()
                .orElseThrow();
        assertEquals(List.of("getter", "unified"), loadedGd.getTags());
    }

    @Test
    void convert_invalidMetadataFailsFast() {
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(InvalidConverterInterfaceEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(MissingNoArgCtorConverterEntity.class));
    }

    // ==================== @Version 乐观锁全路径回归 ====================

    @Test
    void version_insertInitializesAndWritesBack_andSingleUpdatePatchEnforceLock() {
        // 1. insert 自动初始化 null 版本为 0L 并回写内存
        VersionedOrder order = new VersionedOrder(1L, "order-1", 100, null);
        joya.of(VersionedOrder.class).insert(order);
        assertEquals(0L, order.getVersion(), "insert 后应将自动初始化的 0L 回写实体内存");
        assertEquals(0L, joya.sql("SELECT version FROM t_versioned_order WHERE id = 1").value(Long.class).orElseThrow());

        // 2. insertIgnoreNull 同样不得跳过 null 版本列
        VersionedOrder order2 = new VersionedOrder(2L, "order-2", null, null);
        joya.of(VersionedOrder.class).insertIgnoreNull(order2);
        assertEquals(0L, order2.getVersion());

        // 3. EntityInsert 未显式传版本列时自动补齐初始值 0L
        joya.of(VersionedOrder.class).insert()
                .value(VersionedOrder::getId, 3L)
                .value(VersionedOrder::getName, "order-3")
                .value(VersionedOrder::getAmount, 300)
                .execute();
        assertEquals(0L, joya.of(VersionedOrder.class).findById(3L).orElseThrow().getVersion());

        // 4. Short 类型 @Version 支持
        ShortVersionEntity sv = new ShortVersionEntity();
        sv.setId(1L);
        sv.setLabel("s1");
        joya.of(ShortVersionEntity.class).insert(sv);
        assertEquals((short) 0, sv.getVer());
        sv.setLabel("s1-updated");
        joya.of(ShortVersionEntity.class).update(sv);
        assertEquals((short) 1, sv.getVer());

        // 5. 模拟两份并发快照
        VersionedOrder copyA = joya.of(VersionedOrder.class).findById(1L).orElseThrow();
        VersionedOrder copyB = joya.of(VersionedOrder.class).findById(1L).orElseThrow();

        copyA.setAmount(150);
        joya.of(VersionedOrder.class).update(copyA);
        assertEquals(1L, copyA.getVersion(), "update 成功后版本号应推进为 1L");

        // 过期副本更新必须抛 OptimisticLockException，且绝不污染内存版本号
        copyB.setAmount(999);
        OptimisticLockException ex = assertThrows(OptimisticLockException.class,
                () -> joya.of(VersionedOrder.class).update(copyB));
        assertEquals(VersionedOrder.class, ex.entityType());
        assertEquals(1L, ex.primaryKey());
        assertEquals(0L, ex.expectedVersion());
        assertEquals(0L, copyB.getVersion(), "冲突失败后内存版本号不得被提前递增");
        assertEquals(150, joya.of(VersionedOrder.class).findById(1L).orElseThrow().getAmount());

        // 6. patch 同样受乐观锁保护；且仅有 version 非空（业务列全为 null）时应拒绝空更新
        VersionedOrder patchOnlyVersion = new VersionedOrder(1L, null, null, 1L);
        assertThrows(IllegalStateException.class, () -> joya.of(VersionedOrder.class).patch(patchOnlyVersion));

        VersionedOrder patchOk = new VersionedOrder(1L, null, 180, 1L);
        joya.of(VersionedOrder.class).patch(patchOk);
        assertEquals(2L, patchOk.getVersion());
        assertEquals("order-1", joya.of(VersionedOrder.class).findById(1L).orElseThrow().getName());
        assertEquals(180, joya.of(VersionedOrder.class).findById(1L).orElseThrow().getAmount());
    }

    @Test
    void version_batchUpdatesAndPatchAllUseBatchExecuteExactAndTwoPhaseMemoryWriteBack() {
        AtomicInteger exactBatchCalls = new AtomicInteger();
        // 构造代理 Executor：故意让普通 batchExecute 返回 SUCCESS_NO_INFO (-2)，验证 @Version 强制走 batchExecuteExact
        Executor.Factory spyFactory = (connections, translator) -> {
            JdbcExecutor delegate = new JdbcExecutor(connections, translator);
            return new Executor() {
                @Override
                public <T> T query(com.sondertara.joya.core.kernel.statement.SqlStatement s,
                                   com.sondertara.joya.core.kernel.executor.ResultSetExtractor<T> e) {
                    return delegate.query(s, e);
                }

                @Override
                public <T> List<T> queryList(com.sondertara.joya.core.kernel.statement.SqlStatement s,
                                             com.sondertara.joya.core.kernel.executor.RowMapper<T> m) {
                    return delegate.queryList(s, m);
                }

                @Override
                public <T> java.util.stream.Stream<T> queryStream(
                        com.sondertara.joya.core.kernel.statement.SqlStatement s,
                        com.sondertara.joya.core.kernel.executor.RowMapper<T> m) {
                    return delegate.queryStream(s, m);
                }

                @Override
                public int update(com.sondertara.joya.core.kernel.statement.SqlStatement s) {
                    return delegate.update(s);
                }

                @Override
                public InsertSlice insertExecute(com.sondertara.joya.core.kernel.statement.SqlStatement s) {
                    return delegate.insertExecute(s);
                }

                @Override
                public BatchSlice batchExecute(BatchSqlStatement batch) {
                    BatchSlice real = delegate.batchExecute(batch);
                    int[] masked = new int[real.batchCounts().length];
                    Arrays.fill(masked, Statement.SUCCESS_NO_INFO);
                    return BatchSlice.of(masked, real.generatedKeys());
                }

                @Override
                public BatchSlice batchExecuteExact(BatchSqlStatement batch) {
                    exactBatchCalls.incrementAndGet();
                    return delegate.batchExecuteExact(batch);
                }

                @Override
                public <T> T withConnection(com.sondertara.joya.core.kernel.connection.ConnectionCallback<T> a) {
                    return delegate.withConnection(a);
                }

                @Override
                public void runScript(java.io.Reader reader) {
                    delegate.runScript(reader);
                }
            };
        };

        JoyaClient spyJoya = JoyaClient.builder(dataSource).executorFactory(spyFactory).build();

        VersionedOrder o1 = new VersionedOrder(10L, "A", 10, null);
        VersionedOrder o2 = new VersionedOrder(20L, "B", 20, null);
        VersionedOrder o3 = new VersionedOrder(30L, "C", 30, null);
        spyJoya.of(VersionedOrder.class).insertAll(List.of(o1, o2, o3));
        assertEquals(0L, o1.getVersion());
        assertEquals(0L, o2.getVersion());
        assertEquals(0L, o3.getVersion());

        // 批量更新全部成功：走 batchExecuteExact 且推进所有实体版本号
        o1.setAmount(11);
        o2.setAmount(22);
        spyJoya.of(VersionedOrder.class).updateAll(List.of(o1, o2), VersionedOrder::getAmount);
        assertEquals(1, exactBatchCalls.get());
        assertEquals(1L, o1.getVersion());
        assertEquals(1L, o2.getVersion());

        // 禁止选择性 updateAll 只指定 @Version 列
        assertThrows(IllegalArgumentException.class,
                () -> spyJoya.of(VersionedOrder.class).updateAll(List.of(o1), VersionedOrder::getVersion));

        // patchAll 跨多组失败（第 1 组 o1 合法、第 2 组 o3 版本过期）：
        // 1) 抛 OptimisticLockException 且事务回滚；
        // 2) 两阶段回写保证第 1 组 o1 的内存版本绝不被提前推进！
        VersionedOrder p1 = new VersionedOrder(10L, "A-new", null, 1L);
        VersionedOrder p3Stale = new VersionedOrder(30L, null, 333, 99L);
        assertThrows(OptimisticLockException.class, () -> spyJoya.tx(tx -> {
            tx.of(VersionedOrder.class).patchAll(List.of(p1, p3Stale));
        }));
        assertEquals(1L, p1.getVersion(), "patchAll 后段组冲突时，前段组实体内存版本号必须保持未修改");
        assertEquals("A", spyJoya.of(VersionedOrder.class).findById(10L).orElseThrow().getName(),
                "事务回滚后数据库记录应还原");
    }

    @Test
    void version_deleteDeleteAllAndUpsertEnforceLockWhileDeleteByIdBypasses() {
        VersionedOrder o1 = new VersionedOrder(100L, "D1", 10, null);
        VersionedOrder o2 = new VersionedOrder(200L, "D2", 20, null);
        joya.of(VersionedOrder.class).insertAll(List.of(o1, o2));

        // 1. save / saveAll 对 @Version 实体禁用原生 MERGE，改为查后插入/乐观锁更新
        o1.setAmount(15);
        joya.of(VersionedOrder.class).save(o1);
        assertEquals(1L, o1.getVersion());

        VersionedOrder staleSave = new VersionedOrder(100L, "stale-overwrite", 999, 0L);
        assertThrows(OptimisticLockException.class, () -> joya.of(VersionedOrder.class).save(staleSave));
        assertEquals(15, joya.of(VersionedOrder.class).findById(100L).orElseThrow().getAmount(),
                "save 不得走原生 MERGE 静默覆盖过期版本");

        VersionedOrder newSave = new VersionedOrder(300L, "D3", 30, null);
        assertThrows(OptimisticLockException.class, () -> joya.tx(tx ->
                tx.of(VersionedOrder.class).saveAll(List.of(newSave, staleSave))));
        assertFalse(joya.of(VersionedOrder.class).existsById(300L));

        // 2. delete(entity) 携带版本条件；过期版本抛 OptimisticLockException
        VersionedOrder staleDel = new VersionedOrder(100L, "D1", 15, 0L);
        assertThrows(OptimisticLockException.class, () -> joya.of(VersionedOrder.class).delete(staleDel));
        assertTrue(joya.of(VersionedOrder.class).existsById(100L));

        // 3. deleteAll(entities) 走 batchExecuteExact，任一行过期即抛 OptimisticLockException 并回滚
        assertThrows(OptimisticLockException.class, () -> joya.tx(tx ->
                tx.of(VersionedOrder.class).deleteAll(List.of(o2, staleDel))));
        assertTrue(joya.of(VersionedOrder.class).existsById(200L));

        // 4. 正确版本的 delete(entity) 与 deleteAll 均成功
        joya.of(VersionedOrder.class).delete(o1);
        assertFalse(joya.of(VersionedOrder.class).existsById(100L));

        // 5. deleteById 属于按主键强制删除，不检查版本
        joya.of(VersionedOrder.class).deleteById(200L);
        assertFalse(joya.of(VersionedOrder.class).existsById(200L));
    }

    @Test
    void version_crossChunkSaveAllAndMultiRunInsertAllDeferMemoryWriteBackUntilAllSucceed() {
        DataOps smallBatchJoya = joya.withSettings(s -> s.toBuilder().batchSize(2).build());

        VersionedOrder e1 = new VersionedOrder(401L, "c1-a", 1, null);
        VersionedOrder e2 = new VersionedOrder(402L, "c1-b", 2, null);
        VersionedOrder e3 = new VersionedOrder(403L, "c2-a", 3, null);
        smallBatchJoya.of(VersionedOrder.class).insertAll(List.of(e1, e2, e3));
        assertEquals(0L, e1.getVersion());
        assertEquals(0L, e2.getVersion());
        assertEquals(0L, e3.getVersion());

        // saveAll 跨越 2 个分片（batchSize=2）：第 1 分片 (e1, e2) 合法、第 2 分片 e3 过期冲突
        e1.setAmount(10);
        e2.setAmount(20);
        VersionedOrder staleE3 = new VersionedOrder(403L, "c2-stale", 99, 77L);
        assertThrows(OptimisticLockException.class, () -> joya.tx(tx ->
                tx.withSettings(s -> s.toBuilder().batchSize(2).build())
                        .of(VersionedOrder.class).saveAll(List.of(e1, e2, staleE3))));
        assertEquals(0L, e1.getVersion(), "跨分片 saveAll 后段失败回滚时，前分片实体内存版本号不得被脏推进");
        assertEquals(0L, e2.getVersion(), "跨分片 saveAll 后段失败回滚时，前分片实体内存版本号不得被脏推进");

        // insertAllIgnoreNull 跨多个列掩码分组：第 1 组成功、第 2 组触发主键冲突，第 1 组实体内存版本不得提前回写为 0L
        VersionedOrder n1 = new VersionedOrder(501L, "run1", null, null);
        VersionedOrder dupRun2 = new VersionedOrder(401L, "run2-dup", 55, null);
        assertThrows(RuntimeException.class, () -> joya.tx(tx ->
                tx.of(VersionedOrder.class).insertAllIgnoreNull(List.of(n1, dupRun2))));
        assertNull(n1.getVersion(), "多分组 insertAllIgnoreNull 后段失败回滚时，前分组实体内存版本不得提前回写");
    }

    @Test
    void version_invalidMetadataFailsFast() {
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(BadVersionTypeEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(DuplicateVersionEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(IdAndVersionEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(GeneratedVersionEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(NonUpdatableVersionEntity.class));
        assertThrows(DbException.class, () -> LocalTableMetaCache.getMetadataContext(ConvertedVersionEntity.class));
    }
}
