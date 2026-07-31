package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.HavingPart;

public class HavingPartParser implements SqlPartParser<HavingPart> {
    private final String queryStr;

    public HavingPartParser(String queryStr) {
        this.queryStr = queryStr;
    }

    @Override
    public HavingPart parse() {
        return new HavingPart(queryStr);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.HAVING;
    }
}
