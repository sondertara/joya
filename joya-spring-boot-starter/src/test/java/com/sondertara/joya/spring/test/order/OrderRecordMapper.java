package com.sondertara.joya.spring.test.order;

import com.sondertara.joya.core.mapper.JoyaMapper;

import java.util.List;
import java.util.Optional;

public interface OrderRecordMapper extends JoyaMapper<OrderRecord> {

    default Optional<OrderRecord> findByOrderNo(String orderNo) {
        return query().where(w -> w.eq(OrderRecord::getOrderNo, orderNo)).one();
    }

    default List<OrderRecord> findByAmountGreaterThanEqual(Integer minAmount) {
        return query().where(w -> w.gte(OrderRecord::getAmount, minAmount)).list();
    }
}
