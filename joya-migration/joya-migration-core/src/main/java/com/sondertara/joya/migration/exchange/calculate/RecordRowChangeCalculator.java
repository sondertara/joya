
package com.sondertara.joya.migration.exchange.calculate;

/**
 * 变化量计算器接口定义
 *
 * @author tang
 */
public interface RecordRowChangeCalculator {

  /**
   * 是否记录无变化的数据
   *
   * @return 是否记录无变化的数据
   */
  boolean isRecordIdentical();

  /**
   * 设置是否记录无变化的数据
   *
   * @param recordOrNot 是否记录无变化的数据
   */
  void setRecordIdentical(boolean recordOrNot);

  /**
   * 是否进行Jdbc的数据类型检查
   *
   * @return 是否进行检查
   */
  boolean isCheckJdbcType();

  /**
   * 设置是否进行Jdbc的数据类型检查
   *
   * @param checkOrNot 是否进行检查
   */
  void setCheckJdbcType(boolean checkOrNot);

  /**
   * 获取JDBC驱动批量读取数据的行数大小
   *
   * @return 批量行数大小
   */
  int getFetchSize();

  /**
   * 设置JDBC驱动批量读取数据的行数大小
   *
   * @param size 批量行数大小
   */
  void setFetchSize(int size);

  /**
   * 设置中断检查函数
   *
   * @param r 函数引用
   */
  void setInterruptCheck(Runnable r);

  /**
   * 执行变化量计算任务
   *
   * @param task    任务描述实体对象
   * @param handler 计算结果回调处理器
   */
  void executeCalculate(TaskParamEntity task, RecordRowHandler handler);
}
