package com.sondertara.joya.core.query.pagination;

import lombok.Data;

import java.io.Serializable;

/**
 * 封装排序参数
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@Data
public class OrderParam implements Serializable {

  /** */
  private static final long serialVersionUID = -7226188298305059440L;
  /** 排序字段 */
  private String fieldName;
  /** 排序方式 asc desc; */
  private OrderBy orderType;

  public OrderParam(String fieldName, OrderBy orderType) {

    this.fieldName = fieldName;
    this.orderType = orderType;
  }

  public OrderParam(String fieldName) {

    this.fieldName = fieldName;
    this.orderType = OrderBy.ASC;
  }

  /**
   * order parameter
   *
   * @author SonderTara
   * @date 2021/11/14 17:55
   */
  public enum OrderBy {
    /** asc */
    ASC,
    /** desc */
    DESC
  }
}
