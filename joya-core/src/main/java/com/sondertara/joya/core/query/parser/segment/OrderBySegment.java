package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.model.OrderByAlias;
import com.sondertara.joya.core.model.OrderByPart;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class OrderBySegment extends AbstractSegment<OrderByPart> {


    public OrderBySegment(OrderByPart orderByPart) {
        super(orderByPart);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.ORDER_BY;
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("ORDER BY ", null);
    }

    @Override
    public StringJoiner segments() {
        StringJoiner joiner = new StringJoiner(", ");
        for (OrderByAlias segment : this.sqlPart.getSegments()) {
            joiner.add(segment.identity() + " " + segment.getOrderBy().name());
        }
        return joiner;
    }
}
