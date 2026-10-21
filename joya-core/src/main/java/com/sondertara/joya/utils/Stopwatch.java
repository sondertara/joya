package com.sondertara.joya.utils;

import java.util.concurrent.TimeUnit;

/**
 * 简单计时器，替代 common-tara 的 Stopwatch。
 *
 * @author huangxiaohu
 */
public class Stopwatch {

    private final long startTime;
    private long elapsedNanos = -1;
    private boolean running;

    private Stopwatch() {
        this.startTime = System.nanoTime();
        this.running = true;
    }

    public static Stopwatch createStarted() {
        return new Stopwatch();
    }

    public void stop() {
        if (running) {
            elapsedNanos = System.nanoTime() - startTime;
            running = false;
        }
    }

    public long elapsed(TimeUnit unit) {
        if (running) {
            return unit.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        }
        return unit.convert(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public long elapsedMillis() {
        return elapsed(TimeUnit.MILLISECONDS);
    }

    @Override
    public String toString() {
        return String.valueOf(elapsedMillis()) + " ms";
    }
}