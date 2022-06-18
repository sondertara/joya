package com.sondertara.joya.core.query.pagination;

import lombok.ToString;

import java.io.Serializable;

/**
 * @author huangxiaohu
 */
@ToString
public class SearchParam implements Serializable {
  private static final long serialVersionUID = -2687523337885804193L;
  private String fieldName;
  private Object fieldValue;
  private FieldParam.Operator operator;

  public SearchParam(String fieldName, Object fieldValue, FieldParam.Operator operator) {
    this.fieldName = fieldName;
    this.fieldValue = fieldValue;
    this.operator = operator;
  }

  public String getFieldName() {
    return this.fieldName;
  }

  public void setFieldName(String fieldName) {
    this.fieldName = fieldName;
  }

  public FieldParam.Operator getOperator() {
    return this.operator;
  }

  public void setOperator(FieldParam.Operator operator) {
    this.operator = operator;
  }

  public Object getFieldValue() {
    return fieldValue;
  }

  public void setFieldValue(Object fieldValue) {
    this.fieldValue = fieldValue;
  }
}
