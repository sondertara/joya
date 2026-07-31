package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.LimitPart;

public class LimitPartParser implements SqlPartParser<LimitPart> {

    private final Integer offset;
    private final Integer limit;

    public LimitPartParser(Integer offset, Integer limit) {
        this.offset = offset;
        this.limit = limit;
    }

    @Override
    public LimitPart parse() {
        return new LimitPart(offset, limit);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.LIMIT;
    }
}
