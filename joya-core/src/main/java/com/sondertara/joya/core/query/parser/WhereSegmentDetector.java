package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.WhereConditionVisitor;
import com.sondertara.joya.core.model.WherePart;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;
import com.sondertara.joya.core.query.parser.chain.AbstractHandlerChain;

/**
 * @author huangxiaohu
 */
public class WhereSegmentDetector extends AbstractHandlerChain<WhereSegment, WhereSegment> {

    public WhereSegmentDetector(WherePart wherePart) {
        super(wherePart.getSegments().toArray(new WhereConditionVisitor[0]));
    }

    WhereSegment getSegment() {
        return getInput();
    }

}
