
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.exchange.MemChannel;
import com.sondertara.joya.migration.exchange.task.TaskParam;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.sql.DataSource;
import java.util.Set;

/**
 * 读取任务线程的入参
 *
 * @author tang
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReaderTaskParam implements TaskParam {

  private MemChannel memChannel;
  private TableDescription tableDescription;
  private ExchangeDatasourceConfiguration configuration;
  private DataSource sourceDataSource;
  private DataSource targetDataSource;
  private Set<String> targetExistTables;
}
