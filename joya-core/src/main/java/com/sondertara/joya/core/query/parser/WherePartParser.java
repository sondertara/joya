package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.WhereConditionVisitor;
import com.sondertara.joya.core.model.WherePart;
import com.sondertara.joya.core.query.LinkType;

import java.util.List;

/**
 * @author huangxiaohu
 */
public class WherePartParser implements SqlPartParser<WherePart> {

    protected final List<WhereConditionVisitor> condition;
    protected final LinkType rootLinkType;

    public WherePartParser(List<WhereConditionVisitor> condition, LinkType rootLinkType) {
        this.condition = condition;
        this.rootLinkType = rootLinkType;
    }

    @Override
    public WherePart parse() {
        return new WherePart(this.condition, rootLinkType);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.WHERE;
    }
}
