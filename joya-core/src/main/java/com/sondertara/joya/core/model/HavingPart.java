package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;

/**
 * @author huangxiaohu
 */
@Getter
public class HavingPart implements SqlPart {
    private final String queryStr;

    public HavingPart(String queryStr) {
        this.queryStr = queryStr;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.HAVING;
    }
}
