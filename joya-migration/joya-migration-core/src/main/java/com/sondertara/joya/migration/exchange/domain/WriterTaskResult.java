
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.migration.exchange.task.TaskResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 写入任务线程的出参
 *
 * @author tang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriterTaskResult implements TaskResult {

  @Builder.Default
  private Map<String, Long> perf = new HashMap<>();

  @Builder.Default
  private Map<String, Throwable> except = new HashMap<>();

  private boolean success;
  private long duration;
  private Throwable throwable;

  @Override
  public void padding() {
    if (!except.isEmpty() && null == throwable) {
      throwable = except.values().stream().findAny().get();
    }
  }
}
