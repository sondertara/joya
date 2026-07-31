
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.migration.exchange.task.TaskResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 读取任务线程的出参
 *
 * @author tang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReaderTaskResult implements TaskResult {

  @Builder.Default
  private Map<String, Long> perf = new HashMap<>();

  @Builder.Default
  private Map<String, Throwable> except = new HashMap<>();

  private String tableNameMapString;

  private long successCount;

  private long failureCount;

  private long recordCount;

  private long totalBytes;

  private Throwable throwable;

  @Override
  public void padding() {
    if (successCount > 0 && null != tableNameMapString) {
      perf.put(tableNameMapString, recordCount);
    }
    if (null != throwable && null != tableNameMapString) {
      except.putIfAbsent(tableNameMapString, throwable);
    }
  }
}
