
package com.sondertara.joya.migration.provider.write;


import com.sondertara.common.spi.ServiceUsed;
import com.sondertara.joya.core.jdbc.support.DbType;

import javax.sql.DataSource;
import java.util.List;

/**
 * 数据同步接口定义
 *
 * @author tang
 */
@ServiceUsed
public interface TableDataWriteProvider {

  /**
   * 获取数据库类型
   *
   * @return 数据库类型
   */
  DbType getProductType();

  /**
   * 获取数据源对象
   *
   * @return DataSource数据源对象
   */
  DataSource getDataSource();

  /**
   * 批量Insert/Update/Delete预处理
   *
   * @param schemaName schema名称
   * @param tableName  table名称
   * @param fieldNames 字段列表
   * @param pks        主键字段列表
   */
  void prepare(String schemaName, String tableName, List<String> fieldNames, List<String> pks);

  /**
   * 批量数据Insert
   *
   * @param records 数据记录
   * @return 返回实际影响的记录条数
   */
  long executeInsert(List<String> fieldNames,List<Object[]> records);

  /**
   * 批量数据Update
   *
   * @param records 数据记录
   * @return 返回实际影响的记录条数
   */
  long executeUpdate(List<String> fieldNames,List<Object[]> records);

  /**
   * 批量数据Delete
   *
   * @param records 数据记录
   * @return 返回实际影响的记录条数
   */
  long executeDelete(List<Object[]> records);
}
