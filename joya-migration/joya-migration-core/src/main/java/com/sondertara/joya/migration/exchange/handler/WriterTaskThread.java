
package com.sondertara.joya.migration.exchange.handler;

import com.sondertara.common.timing.Stopwatch;
import com.sondertara.joya.migration.exchange.BatchElement;
import com.sondertara.joya.migration.exchange.MemChannel;
import com.sondertara.joya.migration.exchange.domain.WriterTaskParam;
import com.sondertara.joya.migration.exchange.domain.WriterTaskResult;
import com.sondertara.joya.migration.exchange.robot.RobotReader;
import com.sondertara.joya.migration.exchange.task.TaskProcessor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * 数据写入线程体（多个表的写）
 *
 * @author tang
 */
@Slf4j
public class WriterTaskThread extends TaskProcessor<WriterTaskResult> {

  private final WriterTaskParam taskParam;

  public WriterTaskThread(WriterTaskParam taskParam) {
    this.taskParam = taskParam;
  }

  @Override
  protected WriterTaskResult doProcess() {
    MemChannel memChannel = this.taskParam.getMemChannel();
    RobotReader robotReader = this.taskParam.getRobotReader();
    WriterTaskResult taskResult = WriterTaskResult.builder().success(true).build();

    Stopwatch stopwatch = Stopwatch.createStarted();

    try {
      BatchElement elem;
      while (!memChannel.isEmpty() || robotReader.getRemainingCount() > 0) {
        elem = memChannel.poll();
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
        if (null != elem) {
          try {
            long ret = elem.getRecords().size();
            if (this.taskParam.isConcurrentWrite()) {
              elem.getHandler().apply(elem.getColumns(), elem.getRecords(), log);
            } else {
              synchronized (this.taskParam) {
                elem.getHandler().apply(elem.getColumns(), elem.getRecords(), log);
              }
            }
            Long count = taskResult.getPerf().get(elem.getTableNameMapString());
            Long total = ret + Optional.ofNullable(count).orElse(0L);
            taskResult.getPerf().put(elem.getTableNameMapString(), total);
          } catch (Throwable t) {
            taskResult.setSuccess(false);
            taskResult.getExcept().putIfAbsent(elem.getTableNameMapString(), t);
          }
        }
      }
    } finally {
      stopwatch.stop();
      taskResult.setDuration(stopwatch.elapsedMillis());
    }
    return taskResult;
  }

  @Override
  public WriterTaskResult exceptProcess(Throwable t) {
    return WriterTaskResult.builder()
        .success(false)
        .duration(0)
        .throwable(t)
        .build();
  }

}
