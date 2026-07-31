package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.model.LimitPart;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class LimitSegment extends AbstractSegment<LimitPart> {
    protected LimitSegment(LimitPart sqlPart) {
        super(sqlPart);
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of(null, null);
    }


    @Override
    public StringJoiner segments() {
        StringJoiner joiner = new StringJoiner("");
        joiner.add("limit ?,?");
        return joiner;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.LIMIT;
    }

    @Override
    public List<Object> parameters() {
        return Arrays.asList(sqlPart.getOffset(), sqlPart.getN());
    }
}
