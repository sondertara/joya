package com.sondertara.joya.core.query;

import com.sondertara.joya.core.builder.SelectBuilder;
import com.sondertara.joya.utils.SqlUtils;

import java.util.List;

/**
 * 条件查询构造器
 *
 * @author huangxiaohu
 */
public class NativeSqlQuery {

  /** 查询语句 */
  private String sqlStr;
  /** 占位符对应的参数值 */
  private List<Object> params;

  private NativeSqlQuery() {}

  protected NativeSqlQuery(String sqlStr, List<Object> params) {
    this.sqlStr = sqlStr;
    this.params = params;
  }

  public static SelectBuilder builder() {
    return new NativeSqlQueryBuilder();
  }

  /**
   * 获取格式化sql
   *
   * @return sql
   */
  public String toFormattedSql() {
    return SqlUtils.formatSql(this.sqlStr);
  }

  public String toSql() {
    return this.sqlStr;
  }

  public List<Object> getParams() {
    return params;
  }

  @Override
  public String toString() {
    return "NativeSqlQuery{" + this.toSql() + "}";
  }
}
