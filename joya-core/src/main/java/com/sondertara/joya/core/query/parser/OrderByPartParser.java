package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.OrderByAlias;
import com.sondertara.joya.core.model.OrderByPart;

import java.util.Set;

public class OrderByPartParser implements SqlPartParser<OrderByPart> {
    protected final Set<OrderByAlias> segments;

    public OrderByPartParser(Set<OrderByAlias> segments) {
        this.segments = segments;
    }

    @Override
    public OrderByPart parse() {
        return new OrderByPart(segments);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.ORDER_BY;
    }
}
