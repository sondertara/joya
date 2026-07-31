
package com.sondertara.joya.migration.service;


import com.sondertara.joya.migration.exchange.AbstractBatchExchanger;
import com.sondertara.joya.migration.exchange.calculate.PrintablePerfStat;
import com.sondertara.joya.migration.exchange.domain.ComparePerfStat;
import com.sondertara.joya.migration.exchange.domain.ExceptPerfStat;
import com.sondertara.joya.migration.exchange.domain.ReaderPerfStat;
import com.sondertara.joya.migration.exchange.domain.ReaderTaskResult;
import com.sondertara.joya.migration.exchange.domain.WriterPerfStat;
import com.sondertara.joya.migration.exchange.domain.WriterTaskResult;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.exchange.robot.RobotWriter;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * 连接读取和写入的交换器
 *
 * @author tang
 */
public class DefaultBatchExchanger extends AbstractBatchExchanger {

    private List<PrintablePerfStat> perfStats;

    public DefaultBatchExchanger(
            Executor readExecutor,
            Executor writeExecutor,
            int channelMaxSize,
            List<PrintablePerfStat> perfStats) {
        super(readExecutor, writeExecutor, channelMaxSize);
        this.perfStats = perfStats;
    }

    @Override
    protected Throwable collectPerfStats(RobotReader reader, RobotWriter writer) {
        Throwable throwable = null;
        Optional<ReaderTaskResult> readResult = reader.getWorkResult();
        Optional<WriterTaskResult> writeResult = writer.getWorkResult();
        if (readResult.isPresent() && writeResult.isPresent()) {
            ReaderTaskResult r = readResult.get();
            WriterTaskResult w = writeResult.get();
            long total = r.getSuccessCount() + r.getFailureCount();

            HashSet<String> hashSet = new HashSet<>(r.getExcept().keySet());
            hashSet.addAll(w.getExcept().keySet());
            long failure = hashSet.size();
            perfStats.add(new ReaderPerfStat(total, failure, r.getTotalBytes()));
            perfStats.add(new WriterPerfStat(w.getDuration()));
            perfStats.add(new ComparePerfStat(r.getPerf(), w.getPerf()));
            perfStats.add(new ExceptPerfStat(r.getExcept(), w.getExcept()));
            throwable = (null != w.getThrowable()) ? w.getThrowable() : r.getThrowable();
        } else {
            if (readResult.isPresent()) {
                ReaderTaskResult r = readResult.get();
                long total = r.getSuccessCount() + r.getFailureCount();
                perfStats.add(new ReaderPerfStat(total, r.getFailureCount(), r.getTotalBytes()));
                throwable = r.getThrowable();
            }
            if (writeResult.isPresent()) {
                WriterTaskResult w = writeResult.get();
                perfStats.add(new WriterPerfStat(w.getDuration()));
                throwable = w.getThrowable();
            }
        }
        return throwable;
    }
}
