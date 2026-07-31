
package com.sondertara.joya.migration.exchange;

import com.sondertara.common.function.Consumer3;
import com.sondertara.common.function.Function3;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;

import java.util.List;

/**
 * 队列中的批元素结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchElement {

  /**
   * 表名映射字符串，形如 A --> B 的形式
   */
  private String tableNameMapString;

  /**
   * 数据写入回调函数
   */
  private Function3<List<String>, List<Object[]>, Logger,Long> handler;

  /**
   * 写入回调函数的第1个参数
   */
  private List<String> columns;

  /**
   * 写入回调函数的第2个参数
   */
  private List<Object[]> records;
}
