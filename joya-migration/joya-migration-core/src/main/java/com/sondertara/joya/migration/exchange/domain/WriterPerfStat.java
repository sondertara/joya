
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.migration.exchange.calculate.PrintablePerfStat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 并发写入统计格式化信息
 *
 * @author tang
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WriterPerfStat extends PrintablePerfStat {

  private long duration;

  @Override
  public String getPrintableString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Total Writer Duration: \t" + (duration / 1000.0) + " s \n");
    return sb.toString();
  }
}
