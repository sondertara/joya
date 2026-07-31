package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;

import java.util.Set;

/**
 * @author huangxiaohu
 */
@Getter
public class GroupByPart implements SqlPart {

    private final Set<ColumnAlias> columns;

    public GroupByPart(Set<ColumnAlias> columns) {
        this.columns = columns;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.GROUP_BY;
    }
}
