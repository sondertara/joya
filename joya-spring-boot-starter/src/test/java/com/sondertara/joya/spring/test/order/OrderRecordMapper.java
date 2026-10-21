package com.sondertara.joya.spring.test.order;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;

import java.util.List;
import java.util.Optional;

public interface OrderRecordMapper extends JoyaMapper<OrderRecord> {

    Optional<OrderRecord> findByOrderNo(String orderNo);

    List<OrderRecord> findByAmountGreaterThanEqual(Integer minAmount);
}
