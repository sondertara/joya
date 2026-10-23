package com.sondertara.joya.core.naming;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.RenderContext;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TableNameResolver} 契约测试：动态表名必须在渲染期一步到位，
 * 覆盖 DQL / DML / 批处理 / schema 限定，并明确"原生 SQL 不在保证范围内"。
 */
class TableNameResolverTest {

    @Entity
    @Table(name = "t_user")
    public static class User {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "t_audit", schema = "sec")
    public static class Audit {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "action_name")
        private String actionName;

        public Long getId() {
            return id;
        }

        public String getActionName() {
            return actionName;
        }
    }

    private static final TableNameResolver MONTHLY = (schema, table) ->
            (schema == null ? "" : schema + ".") + table + "_202610";

    private static EntityMetadataContext meta(Class<?> type) {
        return CacheHolder.tableMetaCache().getMetadataContext(type);
    }

    @Test
    void tableNameResolver_appliesToDqlRendering() {
        RenderContext ctx = RenderContext.forSingleTable(meta(User.class),
                new NamingPolicy(10, false, false, MONTHLY));

        assertEquals("t_user_202610", ctx.renderFromClause());
    }

    @Test
    void tableNameResolver_appliesToDmlRendering() {
        EntityMetadataContext meta = meta(User.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 1L);
        values.put("name", "alice");
        MutationContext ctx = new MutationContext(meta, values, null,
                new LinkedHashSet<>(meta.columnNames()), false, false,
                new NamingPolicy(10, false, false, MONTHLY));

        SqlStatement stmt = InsertStatementFactory.create(ctx);

        assertTrue(stmt.sql().startsWith("INSERT INTO t_user_202610 "), stmt.sql());
    }

    @Test
    void tableNameResolver_appliesToBatchRendering() {
        EntityMetadataContext meta = meta(User.class);
        BatchSqlStatement batch = InsertStatementFactory.createBatch(
                meta,
                List.of("id", "name"),
                List.<Object[]>of(new Object[]{1L, "alice"}),
                new NamingPolicy(10, false, false, MONTHLY),
                100,
                false);

        assertTrue(batch.sql().startsWith("INSERT INTO t_user_202610 "), batch.sql());
        assertEquals("t_user_202610", batch.metadata().tables().iterator().next().qualifiedName());
    }

    @Test
    void tableNameResolver_preservesSchemaWhenItChoosesTo() {
        RenderContext ctx = RenderContext.forSingleTable(meta(Audit.class),
                new NamingPolicy(10, false, false, MONTHLY));

        assertEquals("sec.t_audit_202610", ctx.renderFromClause());
    }

    @Test
    void tableNameResolver_receivesAlreadyFormattedNames() {
        NamingPolicy policy = new NamingPolicy(10, false, true,
                (schema, table) -> table + "_01");

        assertEquals("T_USER_01", policy.resolveTable(null, "t_user"));
    }

    @Test
    void clientSettingsPropagateResolverToQueryRendering() {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolver_wiring;DB_CLOSE_DELAY=-1");

        com.sondertara.joya.core.JoyaClient client = com.sondertara.joya.core.JoyaClient.builder(dataSource)
                .settings(com.sondertara.joya.core.JoyaSettings.builder()
                        .tableNameResolver(MONTHLY)
                        .build())
                .build();

        SqlStatement stmt = client.from(User.class).toSqlStatement();

        assertTrue(stmt.sql().contains("FROM t_user_202610 t0"), stmt.sql());
        assertEquals("t_user_202610",
                stmt.metadata().tables().iterator().next().qualifiedName());
    }

    @Test
    void rawSqlCarriesNoTableMetadata_byDesign() {
        SqlStatement raw = SqlStatement.select("SELECT * FROM t_user WHERE id = ?", 1L);

        assertTrue(raw.metadata().tables().isEmpty(),
                "原生 SQL 不承诺表元数据；动态表名只对框架生成的 SQL 生效");
    }

    @Test
    void defaultPolicyIsIdentityAndKeepsExistingBehavior() {
        Set<String> tables = Set.of("t_user");

        assertEquals("t_user", NamingPolicy.DEFAULT.resolveTable(null, "t_user"));
        assertEquals("sec.t_audit", NamingPolicy.DEFAULT.resolveTable("sec", "t_audit"));
        assertEquals(1, tables.size());
    }
}
