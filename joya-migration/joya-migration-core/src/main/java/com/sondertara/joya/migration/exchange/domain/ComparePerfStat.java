
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.migration.exchange.calculate.PrintablePerfStat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 比较信息格式化输出
 *
 * @author tang
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComparePerfStat extends PrintablePerfStat {

  private Map<String, Long> readMap;

  private Map<String, Long> writeMap;

  @Override
  public String getPrintableString() {
    StringBuilder sb = new StringBuilder();
    if (readMap.size() > 0) {
      sb.append("Table Detail Information Follows:\n");
      for (Map.Entry<String, Long> entry : readMap.entrySet()) {
        String tableMapName = entry.getKey();
        Long tableReadTotal = entry.getValue();
        Long tableWriteTotal = writeMap.getOrDefault(tableMapName, 0L);
        sb.append(" " + tableMapName + " [read: " + tableReadTotal + ", write:" + tableWriteTotal + "] \n");
      }
    }
    return sb.toString();
  }

}
