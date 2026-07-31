package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.ColumnPair;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Getter;
import lombok.ToString;

import java.util.Set;

/**
 * @author huangxiaohu
 */
@Getter
@ToString
public class SelectPart implements SqlPart {

    private final Set<ColumnPair> segments;


    private final Set<TableAlias> allTables;

    public SelectPart(Set<TableAlias> allTables, Set<ColumnPair> segments) {

        this.segments = segments;
        this.allTables = allTables;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.SELECT;
    }
}
