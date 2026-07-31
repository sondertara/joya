
package com.sondertara.joya.migration.exchange;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

public class MemChannel extends ArrayBlockingQueue<BatchElement> {

    private static final int DEFAULT_QUEUE_MIN_SIZE = 10;

    public static MemChannel createNewChannel(int capacity) {
        if (capacity < DEFAULT_QUEUE_MIN_SIZE) {
            capacity = DEFAULT_QUEUE_MIN_SIZE;
        }
        return new MemChannel(capacity);
    }

    public MemChannel(int capacity) {
        super(capacity, true);
    }

    @Override
    public boolean add(BatchElement elem) {
        try {
            super.put(elem);
            return true;
        } catch (InterruptedException e) {
            throw new CancellationException("task is interrupted");
        }
    }

    @Override
    public BatchElement poll() {
        try {
            return super.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

}
