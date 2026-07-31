
package com.sondertara.joya.migration.service;


import com.sondertara.common.base.Assert;
import com.sondertara.common.collection.Maps;
import com.sondertara.joya.migration.exchange.domain.WriterTaskParam;
import com.sondertara.joya.migration.exchange.domain.WriterTaskResult;
import com.sondertara.joya.migration.exchange.handler.WriterTaskThread;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.exchange.robot.RobotWriter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 目标数据库表并发写入控制
 *
 * @author tang
 */
@Slf4j
public class DefaultWriterRobot extends RobotWriter<WriterTaskResult> {

    private final RobotReader robotReader;
    private final int writeThreadNum;
    private final boolean supportConcurrentWrite;
    private Executor threadExecutor;
    private List<Supplier> writeTaskThreads;
    private List<CompletableFuture> futures;

    public DefaultWriterRobot(RobotReader robotReader, int writeThreadNum,
                              boolean concurrentWrite) {
        Assert.isTrue(writeThreadNum > 0, "writeThreadNum({}) must >0 ", writeThreadNum);
        this.robotReader = robotReader;
        this.writeThreadNum = writeThreadNum;
        this.supportConcurrentWrite = concurrentWrite;
    }

    @Override
    public void interrupt() {
        Optional.ofNullable(futures).orElseGet(ArrayList::new).forEach(f -> f.cancel(true));
    }

    @Override
    public boolean init(Executor threadExecutor) {
        this.threadExecutor = threadExecutor;
        this.writeTaskThreads = new ArrayList<>();

        WriterTaskParam param = WriterTaskParam
                .builder()
                .robotReader(robotReader)
                .memChannel(robotReader.getChannel())
                .concurrentWrite(supportConcurrentWrite)
                .build();
        for (int i = 0; i < writeThreadNum; ++i) {
            writeTaskThreads.add(new WriterTaskThread(param));
        }
        return true;
    }

    @Override
    public void shutdown() {

    }


    @Override
    public void startup() {
        futures = new ArrayList<>(writeTaskThreads.size());
        writeTaskThreads.forEach(
                task ->
                        futures.add(CompletableFuture.supplyAsync(task, threadExecutor))
        );
    }

    @Override
    public void waitForFinish() {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    @Override
    public Optional<WriterTaskResult> getWorkResult() {
        return futures.stream().map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .map(f -> (WriterTaskResult) f)
                .peek(f -> f.padding())
                .reduce(
                        (r1, r2) -> {
                            Map<String, Long> perf = Maps.newHashMap(r1.getPerf());
                            if (r2.isSuccess()) {
                                r2.getPerf().forEach((k, v) -> {
                                    Long count = Optional.ofNullable(perf.get(k)).orElse(0L) + v;
                                    perf.put(k, count);
                                });
                            }
                            Map<String, Throwable> except = Maps.newHashMap(r1.getExcept());
                            if (r2.getExcept().size() > 0) {
                                except.putAll(r2.getExcept());
                            }
                            return WriterTaskResult.builder()
                                    .perf(perf)
                                    .except(except)
                                    .success(r1.isSuccess() && r2.isSuccess())
                                    .duration(Math.max(r1.getDuration(), r2.getDuration()))
                                    .throwable(Objects.nonNull(r1.getThrowable()) ? r1.getThrowable() : r2.getThrowable())
                                    .build();
                        }
                );
    }

}
