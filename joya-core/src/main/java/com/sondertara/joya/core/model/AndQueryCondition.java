package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.core.query.parser.chain.HandlerChain;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;

import java.util.List;

public class AndQueryCondition extends ConditionSegment {


    public AndQueryCondition(List<WhereConditionVisitor> conditions, boolean subQuery) {
        super(conditions, subQuery);
    }


    @Override
    public Operator operator() {
        return Operator.AND;
    }

    @Override
    public void process(WhereSegment input, WhereSegment result, HandlerChain<WhereSegment, WhereSegment> chain) {

        //sub part
        WherePart wherePart = new WherePart(getConditions(), LinkType.AND);

        WhereSegment whereSegment = new WhereSegment(wherePart, input.getParameters());
        if (isSubQuery()) {
            input.getSegments().add("(" + whereSegment.getSegments() + ")");
        } else {
            input.getSegments().merge(whereSegment.getSegments());
        }
        chain.handle(input, result);
    }
}
