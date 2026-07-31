
package com.sondertara.joya.migration.features;

/**
 * 数据库特征定义
 */
public interface ProductFeatures {

  /**
   * FetchSize转换
   *
   * @param fetchSize
   * @return 转换后的fetchSize
   */
  int convertFetchSize(int fetchSize);

}
