package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.core.query.parser.chain.Processor;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;

public interface WhereConditionVisitor extends Processor<WhereSegment, WhereSegment> {

    Operator operator();

    default Object[] parameters() {
        return new Object[0];
    }
}
