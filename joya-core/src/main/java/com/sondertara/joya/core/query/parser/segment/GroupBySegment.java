package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.GroupByPart;
import com.sondertara.joya.core.model.OrderByAlias;
import com.sondertara.joya.core.model.SqlPart;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.StringJoiner;

public class GroupBySegment extends AbstractSegment<GroupByPart> {


    public GroupBySegment(GroupByPart sqlPart) {
        super(sqlPart);
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("GROUP BY ", null);
    }

    @Override
    public StringJoiner segments() {
        StringJoiner joiner = new StringJoiner(", ");
        for (ColumnAlias segment : this.sqlPart.getColumns()) {
            joiner.add(segment.identity());
        }
        return joiner;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.GROUP_BY;
    }
}
