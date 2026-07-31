package com.sondertara.joya.core.query.parser;

/**
 * @author huangxiaohu
 */
public interface SqlParser<T> {

    T parse();

    ParserTypeEnum type();


    default String group() {
        return "generic";
    }
}
