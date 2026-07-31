package com.sondertara.joya.core.query.parser.chain;

import lombok.Getter;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author huangxiaohu
 */
public abstract class AbstractHandlerChain<T, R> implements HandlerChain<T, R> {
    private static final AtomicInteger count = new AtomicInteger(1);

    private final Iterator<Processor<T, R>> iterator;
    @Getter
    private T input;
    @Getter
    private R result;
    private final List<Processor<T, R>> processors;


    @SafeVarargs
    public AbstractHandlerChain(Processor<T, R>... filters) {
        this.processors = Arrays.asList(filters);
        this.iterator = this.processors.iterator();
    }

    @Override
    public void handle(T input, R result) {

        if (iterator.hasNext()) {
            iterator.next().process(input, result, this);
        }
        this.input = input;
        this.result = result;
    }
}
