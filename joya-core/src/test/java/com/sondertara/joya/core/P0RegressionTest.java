package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.dialect.Dialect;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.LockMode;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.query.PageResult;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.PageQueryRequest;
import com.sondertara.joya.core.query.pagination.PageRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 回归：分页切片、页大小上限、参数契约、自定义方言与 count 锁剥离。
 */
class P0RegressionTest {

    private JdbcDataSource dataSource;
    private JoyaClient joya;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:p0_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        joya = JoyaClient.create(dataSource);
        joya.sql("CREATE TABLE t_p0 (id BIGINT PRIMARY KEY, name VARCHAR(32))").update();
        joya.sql("INSERT INTO t_p0(id, name) VALUES (1, 'a'), (2, 'b')").update();
    }

    @Test
    void slicePagination_lastPageMustNotReportHasNext() {
        PageResult<Map> first = joya.sql("SELECT id, name FROM t_p0 ORDER BY id")
                .page(Map.class, PageRequest.of(0, 1).withoutTotal());
        assertEquals(1, first.getData().size());
        assertTrue(first.hasNext(), "first page has a next page");
        assertEquals(-1L, first.total(), "needTotal=false must not report a total");

        PageResult<Map> last = joya.sql("SELECT id, name FROM t_p0 ORDER BY id")
                .page(Map.class, PageRequest.of(1, 1).withoutTotal());
        assertEquals(1, last.getData().size());
        assertFalse(last.hasNext(), "last page must not report a next page");
    }

    @Test
    void slicePagination_exactlyOneRowOnFirstPageMustNotReportHasNext() {
        PageResult<Map> page = joya.sql("SELECT id, name FROM t_p0 WHERE id = 1")
                .page(Map.class, PageRequest.of(0, 1).withoutTotal());
        assertEquals(1, page.getData().size());
        assertFalse(page.hasNext(), "one row with pageSize=1 is the last page");
    }

    @Test
    void pageSizeIsCapped() {
        assertThrows(IllegalArgumentException.class,
                () -> PageRequest.of(0, PageRequest.MAX_PAGE_SIZE + 1));
        assertEquals(PageRequest.MAX_PAGE_SIZE,
                new PageQueryRequest(1, PageRequest.MAX_PAGE_SIZE + 1).normalizedPageSize());
    }

    @Test
    void addCondition_rejectsPlaceholderParameterMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new WhereCriterion().addCondition("age > ? AND status = ?", 18));
        // 正确数量不受影响
        new WhereCriterion().addCondition("age > ? AND status = ?", 18, 1);
    }

    @Test
    void rawSql_rejectsPlaceholderParameterMismatchBeforeExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_p0 WHERE id = ? AND name = ?", 1L).listMap());
        assertThrows(IllegalArgumentException.class,
                () -> joya.sql("SELECT * FROM t_p0 WHERE id = ?", 1L, 2L).listMap());
    }

    @Test
    void countWrapper_stripsTopLevelForUpdate() {
        SqlStatement count = Dialect.POSTGRESQL.countSql(
                SqlStatement.select("SELECT * FROM t_p0 FOR UPDATE"));
        assertFalse(count.sql().toUpperCase().contains("FOR UPDATE"), count.sql());
        assertTrue(count.sql().startsWith("SELECT COUNT(*)"), count.sql());
    }


    @Test
    void countWrapperAliasAvoidsInnerCollision() {
        SqlNode.Table table = SqlNodes.table("t", "t", "TEMP_T");
        SqlNode.Select select = new SqlNode.Select(false,
                List.of(SqlNodes.column("TEMP_T", "id")),
                new SqlNode.From(table, List.of()),
                null, List.of(), null, List.of(), LockMode.NONE);
        SqlStatement count = SqlRenderer.render(new SqlNode.Count(select), StatementMetadata.of(Operation.SELECT));
        assertTrue(count.sql().contains(") TEMP_T_1"), count.sql());
    }

    @Test
    void customDialect_isNotBypassedByDbTypeInjection() {
        Dialect custom = new Dialect() {
            @Override
            public DbType dbType() {
                return DbType.h2;
            }

            @Override
            public SqlStatement countSql(SqlStatement statement) {
                return Dialect.H2.countSql(statement);
            }

            @Override
            public SqlStatement pageSql(SqlStatement statement, long offset, long limit) {
                throw new IllegalStateException("CUSTOM_DIALECT_PAGE");
            }
        };
        JoyaClient customJoya = JoyaClient.builder(dataSource)
                .dialectStrategy(DialectStrategy.fixed(custom))
                .build();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> customJoya.sql("SELECT id FROM t_p0 ORDER BY id")
                        .page(Map.class, PageRequest.of(0, 1)));
        assertEquals("CUSTOM_DIALECT_PAGE", error.getMessage());
    }
}
