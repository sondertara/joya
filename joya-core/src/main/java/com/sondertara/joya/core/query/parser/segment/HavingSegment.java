package com.sondertara.joya.core.query.parser.segment;


import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.model.HavingPart;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.StringJoiner;

/**
 * @author huangxiaohu.1ih
 */
public class HavingSegment extends AbstractSegment<HavingPart> {
    protected HavingSegment(HavingPart sqlPart) {
        super(sqlPart);
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("HAVING ", null);
    }

    @Override
    public StringJoiner segments() {
        return new StringJoiner("").add(sqlPart.getQueryStr());
    }


    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.HAVING;
    }

}
