
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.common.text.unit.DataSizeUtils;
import com.sondertara.joya.migration.exchange.calculate.PrintablePerfStat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 并发读取统计格式化信息
 *
 * @author tang
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReaderPerfStat extends PrintablePerfStat {

  private long total;
  private long failure;
  private long bytes;

  @Override
  public String getPrintableString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Total Read Tables Count: \t" + total + "\n");
    sb.append("Total Failure Tables Count: \t" + failure + "\n");
    sb.append("Total Read Record Size: \t" + DataSizeUtils.format(bytes) + "\n");
    return sb.toString();
  }
}
