package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.core.query.parser.chain.HandlerChain;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;

import java.util.List;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class BetweenCondition extends Condition {

    private final Object left;
    private final Object right;

    public BetweenCondition(String fieldName, Object left, Object right, boolean between) {
        super(fieldName, null, between ? Operator.BETWEEN : Operator.NOT_BETWEEN);
        this.left = left;
        this.right = right;
    }

    @Override
    public void process(WhereSegment input, WhereSegment result, HandlerChain<WhereSegment, WhereSegment> chain) {
        StringJoiner segments = input.getSegments();
        List<Object> parameters = input.getParameters();

        StringBuilder sb = new StringBuilder();
        int prevIndex = parameters.size();

        sb.append(fieldName).append(operator.getConnector()).append(" ?").append(prevIndex + 1).append(" AND ?").append(prevIndex + 2);
        parameters.add(left);
        parameters.add(right);
        segments.add(sb);
        chain.handle(input, result);
    }
}
