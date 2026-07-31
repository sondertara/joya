
package com.sondertara.joya.migration.exchange.domain;

import lombok.Data;

/**
 * 全局参数配置
 *
 * @author tang
 */
@Data
public class GlobalParamConfigProperties {

  private int channelQueueSize;

  private int writeThreadNum;

  public GlobalParamConfigProperties() {
    this.channelQueueSize = 100;
    this.writeThreadNum = getDefaultWriteThreadNum();
  }

  private int getDefaultWriteThreadNum() {
    int availableProcessorCount = Runtime.getRuntime().availableProcessors();
    return Math.min(Math.max(4, availableProcessorCount), 8);
  }

}
