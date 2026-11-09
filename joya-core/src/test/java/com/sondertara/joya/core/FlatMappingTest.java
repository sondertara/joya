package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.exceptions.DbException;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 侧车扁平映射：关联字段不是列，join 只能通过标量 FK 显式完成。
 */
class FlatMappingTest {

    @Entity
    @Table(name = "t_flat_user")
    public static class FlatUser {
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

    @Entity
    @Table(name = "t_flat_order")
    public static class FlatOrder {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "buyer_id")
        private Long buyerId;

        @Column(name = "amount")
        private BigDecimal amount;

        @ManyToOne
        @JoinColumn(name = "buyer_id")
        private FlatUser buyer;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getBuyerId() {
            return buyerId;
        }

        public void setBuyerId(Long buyerId) {
            this.buyerId = buyerId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public FlatUser getBuyer() {
            return buyer;
        }

        public void setBuyer(FlatUser buyer) {
            this.buyer = buyer;
        }
    }

    @Test
    void associationFieldIsNotAColumn_andExplicitFkJoinWorks() {
        EntityMetadataContext meta = LocalTableMetaCache.getMetadataContext(FlatOrder.class);

        assertTrue(meta.columnNames().containsAll(List.of("id", "buyer_id", "amount")));
        assertFalse(meta.columnNames().contains("buyer"), "association must not become a column");
        assertFalse(meta.fieldNames().contains("buyer"), "association must be ignored by metadata");

        SqlStatement select = Query.of(FlatOrder.class).toSqlStatement();
        assertEquals("SELECT t0.id, t0.buyer_id, t0.amount FROM t_flat_order t0", select.sql());

        SqlStatement join = Query.of(FlatOrder.class)
                .join(FlatOrder::getBuyerId, FlatUser::getId)
                .toSqlStatement();
        assertTrue(join.sql().contains("JOIN"), join.sql());
        assertTrue(join.sql().contains("buyer_id"), join.sql());
    }

    @Test
    void associationGetterIsRejectedAsColumnReference() {
        assertThrows(DbException.class,
                () -> Query.of(FlatOrder.class)
                        .where(w -> w.eq(FlatOrder::getBuyer, new FlatUser()))
                        .toSqlStatement());
    }
}
