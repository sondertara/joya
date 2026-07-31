
package com.sondertara.joya.migration.exchange.calculate;

/**
 * 记录变化状态枚举类
 *
 * @author tang
 */
public enum RowChangeTypeEnum {
  /**
   * 未变标识
   */
  VALUE_IDENTICAL(0, "identical"),

  /**
   * 更新标识
   */
  VALUE_CHANGED(1, "update"),

  /**
   * 插入标识
   */
  VALUE_INSERT(2, "insert"),

  /**
   * 删除标识
   */
  VALUE_DELETED(3, "delete"),

  VALUE_IGNORED(4, "ignore");

  /**
   * index
   */
  private Integer index;

  /**
   * 状态标记
   */
  private String status;

  RowChangeTypeEnum(int idx, String flag) {
    this.index = idx;
    this.status = flag;
  }

  public int getIndex() {
    return index;
  }

  public String getStatus() {
    return this.status;
  }

}
