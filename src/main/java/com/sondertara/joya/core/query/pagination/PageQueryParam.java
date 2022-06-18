package com.sondertara.joya.core.query.pagination;

import com.google.common.collect.Lists;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * pagination param
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@EqualsAndHashCode(callSuper = true)
public class PageQueryParam extends JoyaQuery implements Serializable {
  /** page size 分页大小 */
  private Integer pageSize = 10;
  /** page start default is zero 页数 默认从0开始 */
  private Integer page = 0;
  /** the query type,default is AND 连接类型 默认and */
  private LinkType linkType = LinkType.AND;
  /** the order param 排序字段 */
  private List<OrderParam> orderList = Lists.newArrayList();

  /** the param of where 搜索参数 */
  private List<SearchParam> params = Lists.newArrayList();

  public enum LinkType {
    /** */
    AND,
    OR
  }

  /**
   * add search param
   *
   * @param filed fileName
   * @param value value
   * @param operator operator
   */
  public void addSearchParam(String filed, Object value, FieldParam.Operator operator) {

    this.params.add(new SearchParam(filed, value, operator));
  }

  public Integer getPageSize() {
    return pageSize;
  }

  public void pageSize(Integer pageSize) {
    this.pageSize = pageSize;
  }

  public Integer getPage() {
    return page;
  }

  public void page(Integer page) {
    this.page = page;
  }

  public void linkType(LinkType linkType) {
    this.linkType = linkType;
  }

  public void setParams(List<SearchParam> params) {
    this.params = params;
  }

  public List<SearchParam> getParams() {
    return params;
  }

  public LinkType getLinkType() {
    return linkType;
  }

  public List<OrderParam> getOrderList() {
    return orderList;
  }

  public void setOrderList(List<OrderParam> orderList) {
    this.orderList = orderList;
  }
}
