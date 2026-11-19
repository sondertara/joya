package com.sondertara.joya.spring.test.mixed;

import com.sondertara.joya.spring.test.order.OrderRecord;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Optional;

/**
 * 供扫描测试验证：多层泛型继承 + 接口级 {@code @Qualifier("order")} 覆盖包级默认数据源。
 */
@Qualifier("order")
public interface QualifiedOrderMapper extends BaseGenericMapper<OrderRecord> {

    default Optional<OrderRecord> findByOrderNo(String orderNo) {
        return findByField(OrderRecord::getOrderNo, orderNo);
    }
}
