package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;

import java.util.Set;

@Getter
public class OrderByPart extends ColumnAlias  implements SqlPart {
    private final Set<OrderByAlias> segments;

    public OrderByPart(Set<OrderByAlias> segments) {
        this.segments = segments;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.ORDER_BY;
    }
}
