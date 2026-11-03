package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.kernel.connection.DataSourceConnectionProvider;
import com.sondertara.joya.core.mapper.JoyaMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.io.StringReader;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务连接来源与逃生舱语义：
 * <ul>
 *   <li>H2：事务连接只来自 {@code ConnectionProvider}，不再旁路读取 {@code DataSource}。</li>
 *   <li>H2：{@code Tx.withConnection / script} 运行于事务连接，随事务提交 / 回滚。</li>
 *   <li>H3：{@code TxEngine} 只产出 {@code TxSession}，{@code Tx} 门面统一为 {@code TxImpl}。</li>
 * </ul>
 */
class TxConnectionSourceTest {

    private JdbcDataSource primary;
    private JdbcDataSource other;

    @BeforeEach
    void setUp() throws Exception {
        primary = new JdbcDataSource();
        primary.setURL("jdbc:h2:mem:tx_src_primary;DB_CLOSE_DELAY=-1");
        other = new JdbcDataSource();
        other.setURL("jdbc:h2:mem:tx_src_other;DB_CLOSE_DELAY=-1");
        for (JdbcDataSource ds : new JdbcDataSource[]{primary, other}) {
            try (var conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS t_src");
                st.execute("CREATE TABLE t_src (id INT PRIMARY KEY)");
            }
        }
    }

    private long count(JdbcDataSource ds) throws Exception {
        try (var conn = ds.getConnection(); Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM t_src")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void jdbcEngine_borrowsFromConnectionProvider_notFromRawDataSource() throws Exception {
        JoyaClient joya = JoyaClient.builder(primary)
                .connectionProvider(new DataSourceConnectionProvider(other))
                .build();

        joya.tx(tx -> tx.sql("INSERT INTO t_src VALUES (1)").update());

        assertEquals(1L, count(other), "事务必须落在 ConnectionProvider 指向的库");
        assertEquals(0L, count(primary), "不得旁路读取 JoyaClient 构造时传入的 DataSource");
    }

    @Test
    void txWithConnection_runsOnTransactionConnection_rollsBackWithTx() throws Exception {
        JoyaClient joya = JoyaClient.create(primary);

        try (Tx tx = joya.tx().begin()) {
            tx.withConnection(conn -> {
                try (Statement st = conn.createStatement()) {
                    return st.executeUpdate("INSERT INTO t_src VALUES (1)");
                }
            });
            tx.rollback();
        }
        assertEquals(0L, count(primary), "Tx.withConnection 的写入应随事务回滚");

        joya.tx(tx -> tx.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                return st.executeUpdate("INSERT INTO t_src VALUES (2)");
            }
        }));
        assertEquals(1L, count(primary), "事务提交后 Tx.withConnection 的写入可见");
    }

    @Test
    void txScript_runsOnTransactionConnection_rollsBackWithTx() throws Exception {
        JoyaClient joya = JoyaClient.create(primary);

        try (Tx tx = joya.tx().begin()) {
            tx.script(new StringReader("INSERT INTO t_src VALUES (7);"));
            tx.rollback();
        }
        assertEquals(0L, count(primary), "Tx.script 的写入应随事务回滚");
    }

    @Test
    void clientWithConnection_isNotPartOfTransaction_documentedContrast() throws Exception {
        JoyaClient joya = JoyaClient.create(primary);

        try (Tx tx = joya.tx().begin()) {
            joya.withConnection(conn -> {
                try (Statement st = conn.createStatement()) {
                    return st.executeUpdate("INSERT INTO t_src VALUES (3)");
                }
            });
            tx.rollback();
        }
        assertEquals(1L, count(primary), "JoyaClient.withConnection 运行于自动提交连接，不受 Tx 回滚影响");
    }

    @Test
    void customEngine_onlyProducesSession_txFacadeIsAlwaysTxImpl() {
        AtomicInteger opened = new AtomicInteger();
        TxEngine counting = (template, options) -> {
            opened.incrementAndGet();
            return TxEngine.JDBC.open(template, options);
        };
        JoyaClient joya = JoyaClient.builder(primary).txEngine(counting).build();

        try (Tx tx = joya.tx().begin()) {
            assertInstanceOf(TxImpl.class, tx);
            assertTrue(!tx.isRollbackOnly());
            tx.rollback();
        }
        assertEquals(1, opened.get());
    }

    @Entity
    @Table(name = "t_src")
    public static class SrcEntity {
        @Id
        private Integer id;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }
    }

    public interface SrcMapper extends JoyaMapper<SrcEntity> {
    }

    @Test
    void txDataOpsEntries_enforceOwnerThreadBeforeTouchingCaches_andBindMapperToOwner() throws Exception {
        JoyaClient joya = new JoyaClient(primary);
        assertSame(joya, joya.mapper(SrcMapper.class).db(), "客户端 Mapper 必须绑定到 JoyaClient 自身");

        try (Tx tx = joya.tx().begin()) {
            assertSame(tx, tx.mapper(SrcMapper.class).db(), "事务 Mapper 必须绑定到 Tx 自身");

            for (Runnable crossThreadCall : new Runnable[]{
                    () -> tx.of(SrcEntity.class),
                    () -> tx.mapper(SrcMapper.class),
                    () -> tx.from(SrcEntity.class),
                    () -> tx.sql("SELECT 1")
            }) {
                ExecutionException ex = assertThrows(ExecutionException.class,
                        () -> CompletableFuture.runAsync(crossThreadCall).get());
                assertInstanceOf(TxException.class, ex.getCause(),
                        "跨线程访问 Tx 任何 DataOps 入口都必须在触碰内部缓存前抛出 TxException（不变量 I9）");
            }
            tx.rollback();
        }
    }
}
