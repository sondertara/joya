package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.LinkType;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;

import java.util.List;

/**
 * @author huangxiaohu
 */
public class WherePart  implements SqlPart{

    @Getter
    private final List<WhereConditionVisitor> segments;

    @Getter
    private final LinkType rootLinkType;

    public WherePart(List<WhereConditionVisitor> segments, LinkType rootLinkType) {
        this.segments = segments;
        this.rootLinkType = rootLinkType;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.WHERE;
    }
}
