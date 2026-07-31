
package com.sondertara.joya.core.jdbc.support.meta;

/**
 * 数据库表类型:视图表、物理表
 *
 * @author tang
 */
public enum ProductTableEnum {
  /**
   * 物理表
   */
  TABLE(0),

  /**
   * 视图表
   */
  VIEW(1);

  private int index;

  ProductTableEnum(int idx) {
    this.index = idx;
  }

  public int getIndex() {
    return index;
  }
}
