package com.sondertara.joya.core.query.parser.chain;


public interface HandlerChain<T,R> {


    void handle(T input,R result);


}
