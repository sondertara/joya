package com.sondertara.joya.core.query.parser;

import com.sondertara.common.struct.Pair;
import com.sondertara.joya.core.query.parser.SqlParser;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public interface SegmentParser extends SqlParser<StringJoiner> {

    @Override
    default StringJoiner parse() {
        return segments();
    }

    Pair<String,String> bound();

    StringJoiner segments();

    default List<Object> parameters() {
        return Collections.emptyList();
    }
}
