package com.sondertara.joya.core.query.parser.chain;


@FunctionalInterface
public interface Processor<T,R> {

    void process(T input, R result,HandlerChain<T,R> chain);
}
