package com.sondertara.joya.core.kernel;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlStatement} record 契约测试：不可变性、防御拷贝、静态工厂语义、参数元数据。
 */
class SqlStatementTest {

    @Test
    void staticFactories_setOperation() {
        assertEquals(Operation.SELECT, SqlStatement.select("SELECT 1").op());
        assertEquals(Operation.INSERT, SqlStatement.insert("INSERT INTO t VALUES (?)", 1).op());
        assertEquals(Operation.UPDATE, SqlStatement.update("UPDATE t SET a = ?", 1).op());
        assertEquals(Operation.DELETE, SqlStatement.delete("DELETE FROM t WHERE id = ?", 1).op());
    }

    @Test
    void staticFactories_carryArgs() {
        SqlStatement s = SqlStatement.select("SELECT * FROM t WHERE a = ? AND b = ?", List.of(1, 2));
        assertEquals(List.of(1, 2), s.args());
        assertEquals(2, s.parameters().size());
    }

    @Test
    void of_marksTablesResolved_andCarriesMetadata() {
        SqlStatement s = SqlStatement.of("SELECT 1", List.of(),
                StatementMetadata.of(Operation.SELECT, DbType.oracle));

        assertEquals("SELECT 1", s.sql());
        assertTrue(s.parameters().isEmpty());
        assertTrue(s.tablesResolved(), "of(...) 工厂应标记表引用已解析");
        assertEquals(Operation.SELECT, s.metadata().operation());
        assertEquals(DbType.oracle, s.metadata().dbType());
    }

    @Test
    void withMethods_returnNewInstance_andDoNotMutateOriginal() {
        SqlStatement original = SqlStatement.select("SELECT * FROM t_user", 1);

        SqlStatement rewritten = original
                .withSql("SELECT * FROM t_user_0")
                .withDbType(DbType.mysql);

        assertNotSame(original, rewritten);
        assertEquals("SELECT * FROM t_user", original.sql(), "原实例不应被改写");
        assertEquals("SELECT * FROM t_user_0", rewritten.sql());
        assertEquals(DbType.mysql, rewritten.metadata().dbType());
        assertEquals(Operation.SELECT, rewritten.op(), "改写不应丢失操作类型");
        assertEquals(List.of(1), rewritten.args(), "改写不应丢失参数");
    }

    @Test
    void withTable_rewritesSqlAndTableMetadata() {
        SqlStatement s = SqlStatement.of("SELECT * FROM t_user WHERE id = ?",
                List.of(SqlParameter.of(1, "id", 1, ParameterSource.WHERE)),
                StatementMetadata.of(Operation.SELECT, Set.of(TableRef.of("t_user"))));

        SqlStatement rewritten = s.withTable("t_user", "t_user_0");

        assertEquals("SELECT * FROM t_user_0 WHERE id = ?", rewritten.sql());
        assertTrue(rewritten.tables().contains(TableRef.of("t_user_0")),
                "tables 元数据应同步改写，供下游分片拦截器识别");
        assertEquals("SELECT * FROM t_user WHERE id = ?", s.sql(), "原实例不应被改写");
    }

    @Test
    void withTable_sameNameIsNoOp() {
        SqlStatement s = SqlStatement.select("SELECT 1");
        assertEquals(s, s.withTable("t_user", "t_user"), "同名改写应返回原实例");
    }

    @Test
    void appendParameter_addsParamWithSequentialIndexAndName() {
        SqlStatement s = SqlStatement.select("SELECT * FROM t WHERE a = ? AND b = ?", List.of(1))
                .appendParameter("b", 2, ParameterSource.WHERE);

        assertEquals(List.of(1, 2), s.args());
        assertEquals(2, s.parameters().get(1).index(), "追加参数应自动重排 index");
        assertEquals("b", s.parameters().get(1).name());
        assertEquals(ParameterSource.WHERE, s.parameters().get(1).source());
    }

    @Test
    void parameters_defensiveCopyAndImmutable() {
        List<SqlParameter> mutable = new ArrayList<>();
        mutable.add(SqlParameter.of(1, "a", 1, ParameterSource.RAW));

        SqlStatement s = SqlStatement.of("SELECT 1", mutable, StatementMetadata.empty());
        mutable.clear();

        assertEquals(1, s.parameters().size(), "record 应防御性拷贝参数列表");
        assertThrows(UnsupportedOperationException.class,
                () -> s.parameters().add(SqlParameter.of(2, "b", 2, ParameterSource.RAW)),
                "语句参数列表应不可变");
    }

    @Test
    void metadata_emptyDefaults() {
        StatementMetadata empty = StatementMetadata.empty();
        assertEquals(null, empty.operation());
        assertEquals(Set.of(), empty.tables());
        assertEquals(null, empty.dbType());
    }

    @Test
    void metadata_builderStyle() {
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .dbType(DbType.postgresql)
                .table(TableRef.of("t_order", "o"))
                .entityType(String.class)
                .build();

        assertEquals(Operation.INSERT, metadata.operation());
        assertEquals(DbType.postgresql, metadata.dbType());
        assertEquals(Set.of(TableRef.of("t_order", "o")), metadata.tables());
        assertEquals(String.class, metadata.entityType());
    }
}
