package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;

/**
 * @author huangxiaohu
 */
@Getter
public class LimitPart implements SqlPart {
    private final Integer offset;
    private final Integer n;

    public LimitPart(Integer offset, Integer n) {
        this.offset = offset;
        this.n = n;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.LIMIT;
    }
}
