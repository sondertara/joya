package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.GroupByPart;

import java.util.Set;

public class GroupByPartParser implements SqlPartParser<GroupByPart> {
    protected final Set<ColumnAlias> columns;

    public GroupByPartParser(Set<ColumnAlias> columns) {
        this.columns = columns;
    }

    @Override
    public GroupByPart parse() {
        return new GroupByPart(columns);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.GROUP_BY;
    }
}
