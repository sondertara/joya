package com.sondertara.joya.core.naming;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.observe.SqlExecutionContext;
import com.sondertara.joya.core.kernel.observe.SqlExecutionListener;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.query.TableScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.ArrayList;
import java.util.List;
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

        public User() {
        }

        public User(Long id, String name) {
            this.id = id;
            this.name = name;
        }

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
        return LocalTableMetaCache.getMetadataContext(type);
    }

    @Test
    void tableNameResolver_appliesToDqlRendering() {
        TableScope scope = TableScope.forSingleTable(meta(User.class),
                new NamingPolicy(10, false, false, MONTHLY));

        assertEquals("t_user_202610",
                SqlRenderer.render(scope.toFromNode(), StatementMetadata.empty()).sql());
    }

    @Test
    void tableNameResolver_appliesToDmlRendering() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolver_dml;DB_CLOSE_DELAY=-1");
        JoyaClient client = JoyaClient.builder(dataSource)
                .settings(JoyaSettings.builder().tableNameResolver(MONTHLY).build())
                .build();

        SqlStatement stmt = client.of(User.class)
                .insert()
                .value(User::getId, 1L)
                .value(User::getName, "alice")
                .toSqlStatement();

        assertTrue(stmt.sql().startsWith("INSERT INTO t_user_202610 "), stmt.sql());
    }

    @Test
    void tableNameResolver_appliesToBatchRendering() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolver_batch;DB_CLOSE_DELAY=-1");
        JoyaClient.create(dataSource).sql("CREATE TABLE t_user_202610 (id BIGINT PRIMARY KEY, name VARCHAR(32))").update();

        List<SqlStatement> observed = new ArrayList<>();
        JoyaClient client = JoyaClient.builder(dataSource)
                .settings(JoyaSettings.builder().tableNameResolver(MONTHLY).build())
                .executionListener(new SqlExecutionListener() {
                    @Override
                    public void beforeExecute(SqlExecutionContext ctx) {
                        observed.add(ctx.statement());
                    }
                })
                .build();

        client.of(User.class).insertAll(List.of(new User(1L, "alice"), new User(2L, "bob")));

        assertEquals(1, observed.size());
        SqlStatement batchProbe = observed.get(0);
        assertTrue(batchProbe.sql().startsWith("INSERT INTO t_user_202610 "), batchProbe.sql());
        assertEquals("t_user_202610", batchProbe.metadata().tables().iterator().next().qualifiedName());
    }

    @Test
    void tableNameResolver_preservesSchemaWhenItChoosesTo() {
        TableScope scope = TableScope.forSingleTable(meta(Audit.class),
                new NamingPolicy(10, false, false, MONTHLY));

        assertEquals("sec.t_audit_202610",
                SqlRenderer.render(scope.toFromNode(), StatementMetadata.empty()).sql());
    }

    @Test
    void tableNameResolver_receivesAlreadyFormattedNames() {
        NamingPolicy policy = new NamingPolicy(10, false, true,
                (schema, table) -> table + "_01");

        assertEquals("T_USER_01", policy.resolveTable(null, "t_user"));
    }

    @Test
    void clientSettingsPropagateResolverToQueryRendering() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolver_wiring;DB_CLOSE_DELAY=-1");

        JoyaClient client = JoyaClient.builder(dataSource)
                .settings(JoyaSettings.builder()
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
