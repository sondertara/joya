package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.joya.core.query.parser.SegmentParser;
import com.sondertara.joya.core.model.SqlPart;

public abstract class AbstractSegment<T extends SqlPart> implements SegmentParser {

    protected final T sqlPart;

    protected AbstractSegment(T sqlPart) {
        this.sqlPart = sqlPart;
    }

    @Override
    public String toString() {
        return "AbstractSegment{" +
                "sqlPart=" + sqlPart +
                "segments=" + segments() +
                "parameters=" + parameters() +
                '}';
    }
}
