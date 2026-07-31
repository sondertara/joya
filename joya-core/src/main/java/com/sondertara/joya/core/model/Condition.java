package com.sondertara.joya.core.model;

import com.sondertara.common.collection.ArrayUtils;
import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.common.exception.TaraException;
import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.core.query.parser.chain.HandlerChain;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;
import com.sondertara.joya.exceptions.SqlParseException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

public class Condition implements WhereConditionVisitor {

    protected final String fieldName;
    protected final Operator operator;
    protected final Object value;


    public Condition(String fieldName, Object value, Operator operator) {
        this.fieldName = fieldName;
        this.operator = operator;
        this.value = value;
    }

    public Condition(String fieldName, Object value) {
        this.fieldName = fieldName;
        this.operator = Operator.EQ;
        this.value = value;
    }


    @Override
    public Operator operator() {
        return this.operator;
    }


    @Override
    public Object[] parameters() {
        return new Object[0];
    }

    @Override
    public void process(WhereSegment input, WhereSegment result, HandlerChain<WhereSegment, WhereSegment> chain) {
        StringJoiner segments = input.getSegments();
        List<Object> parameters = input.parameters();
        int prevIndex = parameters.size();
        int lastIndex = prevIndex + 1;
        StringBuilder sb = new StringBuilder();
        switch (operator) {
            case IS_NULL:
            case IS_NOT_NULL:
                break;
            case IN:
                if (ArrayUtils.isArray(value)) {
                    List<Object> list = Arrays.asList((Object[]) value);
                    parameters.addAll(list);
                    lastIndex = prevIndex + list.size();

                } else if (value instanceof Collection) {
                    Collection<?> objects = (Collection<?>) value;
                    lastIndex = prevIndex + objects.size();
                    parameters.addAll(objects);
                } else {
                    throw new TaraException("The value of operator [IN] must be  collection");
                }
                StringJoiner stringJoiner = new StringJoiner(",");
                for (int i = prevIndex + 1; i <= lastIndex; i++) {
                    stringJoiner.add("?" + i);
                }
                String param = '(' + stringJoiner.toString() + ')';
                sb.append(fieldName).append(operator.getConnector()).append(param);
                break;
            case LIKE:
            case NOT_LIKE:
                parameters.add("%" + ConvertUtils.convert(String.class, value) + "%");
                break;
            case LIKE_L:
            case NOT_LIKE_L:
                parameters.add("%" + ConvertUtils.convert(String.class, value));
                break;
            case LIKE_R:
            case NOT_LIKE_R:
                parameters.add(ConvertUtils.convert(String.class, value) + "%");
                break;
            case BETWEEN:
            case NOT_BETWEEN:
                throw new SqlParseException("Please use BetweenCondition.class");
            default:
                if (operator.getParamCount() == 1) {
                    parameters.add(value);
                }
        }

        if (sb.length() == 0) {
            sb.append(fieldName).append(operator.getConnector());
            if (operator.getParamCount() == 1) {
                sb.append("?").append(lastIndex);
            }
        }
        segments.add(sb);


        chain.handle(input, result);
    }
}
