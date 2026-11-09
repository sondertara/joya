package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.interceptor.SqlRewriteException;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SqlRewriter 契约：不改投影、不换语句类型、不产生重复列。
 */
class SqlRewriterContractTest {

    @Entity
    @Table(name = "t_contract_user")
    public static class ContractUser {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

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
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rewriter_contract_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JoyaClient.builder(dataSource).build()
                .sql("CREATE TABLE t_contract_user (id BIGINT PRIMARY KEY, name VARCHAR(32))")
                .update();
    }

    @Test
    void rewriterMustNotChangeSelectProjection() {
        JoyaClient client = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Select select) {
                        return new SqlNode.Select(select.distinct(),
                                List.of(SqlNodes.raw("id")),
                                select.from(), select.where(), select.groupBy(), select.having(),
                                select.orderBy(), select.lockMode());
                    }
                    return node;
                })
                .build();

        assertThrows(SqlRewriteException.class, () -> client.from(ContractUser.class).list());
    }

    @Test
    void rewriterMustNotChangeOperationKind() {
        JoyaClient client = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> node instanceof SqlNode.Update update
                        ? new SqlNode.Delete(update.table(), update.where())
                        : node)
                .build();

        assertThrows(SqlRewriteException.class, () -> client.of(ContractUser.class).update()
                .set(ContractUser::getName, "x")
                .where(w -> w.eq(ContractUser::getId, 1L))
                .execute());
    }

    @Test
    void rewriterMustNotCreateDuplicateUpdateAssignments() {
        JoyaClient client = JoyaClient.builder(dataSource)
                .rewriter((node, ctx) -> {
                    if (node instanceof SqlNode.Update update) {
                        List<SqlNode.Assignment> assignments = new java.util.ArrayList<>(update.assignments());
                        assignments.add(SqlNodes.assignment("name", SqlNodes.param("name", "y",
                                com.sondertara.joya.core.kernel.statement.ParameterSource.SET)));
                        return new SqlNode.Update(update.table(), assignments, update.where());
                    }
                    return node;
                })
                .build();

        assertThrows(SqlRewriteException.class, () -> client.of(ContractUser.class).update()
                .set(ContractUser::getName, "x")
                .where(w -> w.eq(ContractUser::getId, 1L))
                .execute());
    }
}
